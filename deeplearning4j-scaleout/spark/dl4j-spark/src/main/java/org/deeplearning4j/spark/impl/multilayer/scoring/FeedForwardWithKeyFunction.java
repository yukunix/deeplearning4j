/*-
 *
 *  * Copyright 2016 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.spark.impl.multilayer.scoring;

import org.apache.spark.broadcast.Broadcast;
import org.datavec.spark.functions.FlatMapFunctionAdapter;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.spark.util.BasePairFlatMapFunctionAdaptee;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.executioner.GridExecutioner;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

import java.util.*;

/**
 * Function to feed-forward examples, and get the network output (for example, class probabilities).
 * A key value is used to keep track of which output corresponds to which input.
 *
 * @param <K> Type of key, associated with each example. Used to keep track of which output belongs to which input example
 * @author Alex Black
 */
public class FeedForwardWithKeyFunction<K>
                extends BasePairFlatMapFunctionAdaptee<Iterator<Tuple2<K, INDArray>>, K, INDArray> {

    public FeedForwardWithKeyFunction(Broadcast<INDArray> params, Broadcast<String> jsonConfig, int batchSize) {
        super(new FeedForwardWithKeyFunctionAdapter<K>(params, jsonConfig, batchSize));
    }
}


/**
 * Function to feed-forward examples, and get the network output (for example, class probabilities).
 * A key value is used to keey track of which output corresponds to which input.
 *
 * @param <K> Type of key, associated with each example. Used to keep track of which output belongs to which input example
 * @author Alex Black
 */
class FeedForwardWithKeyFunctionAdapter<K>
                implements FlatMapFunctionAdapter<Iterator<Tuple2<K, INDArray>>, Tuple2<K, INDArray>> {

    protected static Logger log = LoggerFactory.getLogger(FeedForwardWithKeyFunction.class);

    private final Broadcast<INDArray> params;
    private final Broadcast<String> jsonConfig;
    private final int batchSize;

    /**
     * @param params     MultiLayerNetwork parameters
     * @param jsonConfig MultiLayerConfiguration, as json
     * @param batchSize  Batch size to use for forward pass (use > 1 for efficiency)
     */
    public FeedForwardWithKeyFunctionAdapter(Broadcast<INDArray> params, Broadcast<String> jsonConfig, int batchSize) {
        this.params = params;
        this.jsonConfig = jsonConfig;
        this.batchSize = batchSize;
    }


    @Override
    public Iterable<Tuple2<K, INDArray>> call(Iterator<Tuple2<K, INDArray>> iterator) throws Exception {
        if (!iterator.hasNext()) {
            return Collections.emptyList();
        }

        MultiLayerNetwork network = new MultiLayerNetwork(MultiLayerConfiguration.fromJson(jsonConfig.getValue()));
        network.init();
        INDArray val = params.value().unsafeDuplication();
        if (val.length() != network.numParams(false))
            throw new IllegalStateException(
                            "Network did not have same number of parameters as the broadcasted set parameters");
        network.setParameters(val);

        //Issue: for 2d data (MLPs etc) we can just stack the examples.
        //But: for 3d and 4d: in principle the data sizes could be different
        //We could handle that with mask arrays - but it gets messy. The approach used here is simpler but less efficient

        List<INDArray> featuresList = new ArrayList<>(batchSize);
        List<K> keyList = new ArrayList<>(batchSize);
        List<Integer> origSizeList = new ArrayList<>();

        int[] firstShape = null;
        boolean sizesDiffer = false;
        int tupleCount = 0;
        while (iterator.hasNext()) {
            Tuple2<K, INDArray> t2 = iterator.next();
            if (firstShape == null) {
                firstShape = t2._2().shape();
            } else if (!sizesDiffer) {
                for (int i = 1; i < firstShape.length; i++) {
                    if (firstShape[i] != featuresList.get(tupleCount - 1).size(i)) {
                        sizesDiffer = true;
                        break;
                    }
                }
            }
            featuresList.add(t2._2());
            keyList.add(t2._1());
            origSizeList.add(t2._2().size(0));
            tupleCount++;
        }

        if (tupleCount == 0) {
            return Collections.emptyList();
        }

        List<Tuple2<K, INDArray>> output = new ArrayList<>(tupleCount);
        int currentArrayIndex = 0;

        while (currentArrayIndex < featuresList.size()) {
            int firstIdx = currentArrayIndex;
            int nextIdx = currentArrayIndex;
            int examplesInBatch = 0;
            List<INDArray> toMerge = new ArrayList<>();
            firstShape = null;
            while (nextIdx < featuresList.size() && examplesInBatch < batchSize) {
                if (firstShape == null) {
                    firstShape = featuresList.get(nextIdx).shape();
                } else if (sizesDiffer) {
                    boolean breakWhile = false;
                    for (int i = 1; i < firstShape.length; i++) {
                        if (firstShape[i] != featuresList.get(nextIdx).size(i)) {
                            //Next example has a different size. So: don't add it to the current batch, just process what we have
                            breakWhile = true;
                            break;
                        }
                    }
                    if (breakWhile) {
                        break;
                    }
                }

                INDArray f = featuresList.get(nextIdx++);
                toMerge.add(f);
                examplesInBatch += f.size(0);
            }

            INDArray batchFeatures = Nd4j.concat(0, toMerge.toArray(new INDArray[toMerge.size()]));
            INDArray out = network.output(batchFeatures, false);

            examplesInBatch = 0;
            for (int i = firstIdx; i < nextIdx; i++) {
                int numExamples = origSizeList.get(i);
                INDArray outputSubset = getSubset(examplesInBatch, examplesInBatch + numExamples, out);
                examplesInBatch += numExamples;

                output.add(new Tuple2<>(keyList.get(i), outputSubset));
            }

            currentArrayIndex += (nextIdx - firstIdx);
        }

        if (Nd4j.getExecutioner() instanceof GridExecutioner)
            ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();

        return output;
    }

    private INDArray getSubset(int exampleStart, int exampleEnd, INDArray from) {
        switch (from.rank()) {
            case 2:
                return from.get(NDArrayIndex.interval(exampleStart, exampleEnd), NDArrayIndex.all());
            case 3:
                return from.get(NDArrayIndex.interval(exampleStart, exampleEnd), NDArrayIndex.all(),
                                NDArrayIndex.all());
            case 4:
                return from.get(NDArrayIndex.interval(exampleStart, exampleEnd), NDArrayIndex.all(), NDArrayIndex.all(),
                                NDArrayIndex.all());
            default:
                throw new RuntimeException("Invalid rank: " + from.rank());
        }
    }
}
