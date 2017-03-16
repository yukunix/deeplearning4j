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

package org.deeplearning4j.spark.impl.multilayer.evaluation;

import org.apache.spark.broadcast.Broadcast;
import org.datavec.spark.functions.FlatMapFunctionAdapter;
import org.datavec.spark.transform.BaseFlatMapFunctionAdaptee;
import org.deeplearning4j.eval.IEvaluation;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Function to evaluate data (using an IEvaluation instance), in a distributed manner
 * Flat map function used to batch examples for computational efficiency + reduce number of IEvaluation objects returned
 * for network efficiency.
 *
 * @author Alex Black
 */
public class IEvaluateFlatMapFunction<T extends IEvaluation> extends BaseFlatMapFunctionAdaptee<Iterator<DataSet>, T> {

    public IEvaluateFlatMapFunction(Broadcast<String> json, Broadcast<INDArray> params, int evalBatchSize,
                    T evaluation) {
        super(new IEvaluateFlatMapFunctionAdapter<>(json, params, evalBatchSize, evaluation));
    }
}


/**
 * Function to evaluate data (using an IEvaluation instance), in a distributed manner
 * Flat map function used to batch examples for computational efficiency + reduce number of IEvaluation objects returned
 * for network efficiency.
 *
 * @author Alex Black
 */
class IEvaluateFlatMapFunctionAdapter<T extends IEvaluation> implements FlatMapFunctionAdapter<Iterator<DataSet>, T> {

    protected static Logger log = LoggerFactory.getLogger(IEvaluateFlatMapFunction.class);

    protected T evaluation;
    protected Broadcast<String> json;
    protected Broadcast<INDArray> params;
    protected int evalBatchSize;

    /**
     * @param json Network configuration (json format)
     * @param params Network parameters
     * @param evalBatchSize Max examples per evaluation. Do multiple separate forward passes if data exceeds
     *                              this. Used to avoid doing too many at once (and hence memory issues)
     * @param evaluation Initial evaulation instance (i.e., empty Evaluation or RegressionEvaluation instance)
     */
    public IEvaluateFlatMapFunctionAdapter(Broadcast<String> json, Broadcast<INDArray> params, int evalBatchSize,
                    T evaluation) {
        this.json = json;
        this.params = params;
        this.evalBatchSize = evalBatchSize;
        this.evaluation = evaluation;
    }

    @Override
    public Iterable<T> call(Iterator<DataSet> dataSetIterator) throws Exception {
        if (!dataSetIterator.hasNext()) {
            return Collections.emptyList();
        }

        MultiLayerNetwork network = new MultiLayerNetwork(MultiLayerConfiguration.fromJson(json.getValue()));
        network.init();
        INDArray val = params.value().unsafeDuplication();
        if (val.length() != network.numParams(false))
            throw new IllegalStateException(
                            "Network did not have same number of parameters as the broadcast set parameters");
        network.setParameters(val);

        List<DataSet> collect = new ArrayList<>();
        int totalCount = 0;
        while (dataSetIterator.hasNext()) {
            collect.clear();
            int nExamples = 0;
            while (dataSetIterator.hasNext() && nExamples < evalBatchSize) {
                DataSet next = dataSetIterator.next();
                nExamples += next.numExamples();
                collect.add(next);
            }
            totalCount += nExamples;

            DataSet data = DataSet.merge(collect);


            INDArray out;
            if (data.hasMaskArrays()) {
                out = network.output(data.getFeatureMatrix(), false, data.getFeaturesMaskArray(),
                                data.getLabelsMaskArray());
            } else {
                out = network.output(data.getFeatureMatrix(), false);
            }


            if (data.getLabels().rank() == 3) {
                if (data.getLabelsMaskArray() == null) {
                    evaluation.evalTimeSeries(data.getLabels(), out);
                } else {
                    evaluation.evalTimeSeries(data.getLabels(), out, data.getLabelsMaskArray());
                }
            } else {
                evaluation.eval(data.getLabels(), out);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Evaluated {} examples ", totalCount);
        }

        return Collections.singletonList(evaluation);
    }
}
