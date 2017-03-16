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

package org.deeplearning4j.spark.impl.common.score;

import lombok.extern.slf4j.Slf4j;
import org.apache.spark.broadcast.Broadcast;
import org.datavec.spark.functions.FlatMapFunctionAdapter;
import org.deeplearning4j.nn.layers.variational.VariationalAutoencoder;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.executioner.GridExecutioner;
import org.nd4j.linalg.factory.Nd4j;
import scala.Tuple2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Function to calculate the scores (reconstruction probability, reconstruction error) for a variational autoencoder.<br>
 * Note that scoring is batched for computational efficiency.<br>
 *
 * @param <K> Type of key, associated with each example. Used to keep track of which score belongs to which example
 * @author Alex Black
 */
@Slf4j
public abstract class BaseVaeScoreWithKeyFunctionAdapter<K>
                implements FlatMapFunctionAdapter<Iterator<Tuple2<K, INDArray>>, Tuple2<K, Double>> {

    protected final Broadcast<INDArray> params;
    protected final Broadcast<String> jsonConfig;
    private final int batchSize;


    /**
     * @param params                 MultiLayerNetwork parameters
     * @param jsonConfig             MultiLayerConfiguration, as json
     * @param batchSize              Batch size to use when scoring
     */
    public BaseVaeScoreWithKeyFunctionAdapter(Broadcast<INDArray> params, Broadcast<String> jsonConfig, int batchSize) {
        this.params = params;
        this.jsonConfig = jsonConfig;
        this.batchSize = batchSize;
    }

    public abstract VariationalAutoencoder getVaeLayer();

    public abstract INDArray computeScore(VariationalAutoencoder vae, INDArray toScore);


    @Override
    public Iterable<Tuple2<K, Double>> call(Iterator<Tuple2<K, INDArray>> iterator) throws Exception {
        if (!iterator.hasNext()) {
            return Collections.emptyList();
        }

        VariationalAutoencoder vae = getVaeLayer();

        List<Tuple2<K, Double>> ret = new ArrayList<>();

        List<INDArray> collect = new ArrayList<>(batchSize);
        List<K> collectKey = new ArrayList<>(batchSize);
        int totalCount = 0;
        while (iterator.hasNext()) {
            collect.clear();
            collectKey.clear();
            int nExamples = 0;
            while (iterator.hasNext() && nExamples < batchSize) {
                Tuple2<K, INDArray> t2 = iterator.next();
                INDArray features = t2._2();
                int n = features.size(0);
                if (n != 1)
                    throw new IllegalStateException("Cannot score examples with one key per data set if "
                                    + "data set contains more than 1 example (numExamples: " + n + ")");
                collect.add(features);
                collectKey.add(t2._1());
                nExamples += n;
            }
            totalCount += nExamples;

            INDArray toScore = Nd4j.vstack(collect);
            INDArray scores = computeScore(vae, toScore);

            double[] doubleScores = scores.data().asDouble();

            for (int i = 0; i < doubleScores.length; i++) {
                ret.add(new Tuple2<>(collectKey.get(i), doubleScores[i]));
            }
        }

        if (Nd4j.getExecutioner() instanceof GridExecutioner)
            ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();

        if (log.isDebugEnabled()) {
            log.debug("Scored {} examples ", totalCount);
        }

        return ret;
    }
}
