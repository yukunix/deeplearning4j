package org.deeplearning4j.spark.impl.paramavg.aggregator;

import org.apache.spark.api.java.function.Function2;
import org.deeplearning4j.api.storage.Persistable;
import org.deeplearning4j.api.storage.StorageMetaData;
import org.deeplearning4j.spark.api.stats.SparkTrainingStats;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.executioner.GridExecutioner;
import org.nd4j.linalg.factory.Nd4j;

import java.util.Collection;

/**
 * Function used in ParameterAveraging TrainingMaster, for doing parameter averaging, and handling updaters
 *
 * @author Alex Black
 */
public class ParameterAveragingElementCombineFunction implements
                Function2<ParameterAveragingAggregationTuple, ParameterAveragingAggregationTuple, ParameterAveragingAggregationTuple> {
    @Override
    public ParameterAveragingAggregationTuple call(ParameterAveragingAggregationTuple v1,
                    ParameterAveragingAggregationTuple v2) throws Exception {
        if (v1 == null)
            return v2;
        else if (v2 == null)
            return v1;

        //Handle edge case of less data than executors: in this case, one (or both) of v1 and v2 might not have any contents...
        if (v1.getParametersSum() == null)
            return v2;
        else if (v2.getParametersSum() == null)
            return v1;

        INDArray newParams = v1.getParametersSum().addi(v2.getParametersSum());
        INDArray updaterStateSum;
        if (v1.getUpdaterStateSum() == null) {
            updaterStateSum = v2.getUpdaterStateSum();
        } else {
            updaterStateSum = v1.getUpdaterStateSum();
            if (v2.getUpdaterStateSum() != null)
                updaterStateSum.addi(v2.getUpdaterStateSum());
        }


        double scoreSum = v1.getScoreSum() + v2.getScoreSum();
        int aggregationCount = v1.getAggregationsCount() + v2.getAggregationsCount();

        SparkTrainingStats stats = v1.getSparkTrainingStats();
        if (v2.getSparkTrainingStats() != null) {
            if (stats == null)
                stats = v2.getSparkTrainingStats();
            else
                stats.addOtherTrainingStats(v2.getSparkTrainingStats());
        }

        if (Nd4j.getExecutioner() instanceof GridExecutioner)
            ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();

        Collection<StorageMetaData> listenerMetaData = v1.getListenerMetaData();
        if (listenerMetaData == null)
            listenerMetaData = v2.getListenerMetaData();
        else {
            Collection<StorageMetaData> newMeta = v2.getListenerMetaData();
            if (newMeta != null)
                listenerMetaData.addAll(newMeta);
        }

        Collection<Persistable> listenerStaticInfo = v1.getListenerStaticInfo();
        if (listenerStaticInfo == null)
            listenerStaticInfo = v2.getListenerStaticInfo();
        else {
            Collection<Persistable> newStatic = v2.getListenerStaticInfo();
            if (newStatic != null)
                listenerStaticInfo.addAll(newStatic);
        }

        Collection<Persistable> listenerUpdates = v1.getListenerUpdates();
        if (listenerUpdates == null)
            listenerUpdates = v2.getListenerUpdates();
        else {
            Collection<Persistable> listenerUpdates2 = v2.getListenerUpdates();
            if (listenerUpdates2 != null)
                listenerUpdates.addAll(listenerUpdates2);
        }

        return new ParameterAveragingAggregationTuple(newParams, updaterStateSum, scoreSum, aggregationCount, stats,
                        listenerMetaData, listenerStaticInfo, listenerUpdates);
    }
}
