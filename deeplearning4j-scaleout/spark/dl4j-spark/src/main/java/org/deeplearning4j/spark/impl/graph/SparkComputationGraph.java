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

package org.deeplearning4j.spark.impl.graph;

import lombok.extern.slf4j.Slf4j;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaDoubleRDD;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.rdd.RDD;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.spark.api.TrainingMaster;
import org.deeplearning4j.spark.api.stats.SparkTrainingStats;
import org.deeplearning4j.spark.impl.SparkListenable;
import org.deeplearning4j.spark.impl.common.reduce.IntDoubleReduceFunction;
import org.deeplearning4j.spark.impl.graph.dataset.DataSetToMultiDataSetFn;
import org.deeplearning4j.spark.impl.graph.dataset.PairDataSetToMultiDataSetFn;
import org.deeplearning4j.spark.impl.graph.scoring.*;
import org.deeplearning4j.spark.util.SparkUtils;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.executioner.GridExecutioner;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.MultiDataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.heartbeat.Heartbeat;
import org.nd4j.linalg.heartbeat.reports.Environment;
import org.nd4j.linalg.heartbeat.reports.Event;
import org.nd4j.linalg.heartbeat.reports.Task;
import org.nd4j.linalg.heartbeat.utils.EnvironmentUtils;
import scala.Tuple2;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main class for training ComputationGraph networks using Spark
 *
 * @author Alex Black
 */
@Slf4j
public class SparkComputationGraph extends SparkListenable {

    public static final int DEFAULT_EVAL_SCORE_BATCH_SIZE = 64;
    private transient JavaSparkContext sc;
    private ComputationGraphConfiguration conf;
    private ComputationGraph network;
    private double lastScore;

    private transient AtomicInteger iterationsCount = new AtomicInteger(0);

    /**
     * Instantiate a ComputationGraph instance with the given context and network.
     *
     * @param sparkContext the spark context to use
     * @param network      the network to use
     */
    public SparkComputationGraph(SparkContext sparkContext, ComputationGraph network, TrainingMaster trainingMaster) {
        this(new JavaSparkContext(sparkContext), network, trainingMaster);
    }

    public SparkComputationGraph(JavaSparkContext javaSparkContext, ComputationGraph network,
                    TrainingMaster trainingMaster) {
        sc = javaSparkContext;
        this.trainingMaster = trainingMaster;
        this.conf = network.getConfiguration().clone();
        this.network = network;
        this.network.init();

        //Check if kryo configuration is correct:
        SparkUtils.checkKryoConfiguration(javaSparkContext, log);
    }


    public SparkComputationGraph(SparkContext sparkContext, ComputationGraphConfiguration conf,
                    TrainingMaster trainingMaster) {
        this(new JavaSparkContext(sparkContext), conf, trainingMaster);
    }

    public SparkComputationGraph(JavaSparkContext sparkContext, ComputationGraphConfiguration conf,
                    TrainingMaster trainingMaster) {
        sc = sparkContext;
        this.trainingMaster = trainingMaster;
        this.conf = conf.clone();
        this.network = new ComputationGraph(conf);
        this.network.init();

        //Check if kryo configuration is correct:
        SparkUtils.checkKryoConfiguration(sparkContext, log);
    }

    public JavaSparkContext getSparkContext() {
        return sc;
    }

    public void setCollectTrainingStats(boolean collectTrainingStats) {
        trainingMaster.setCollectTrainingStats(collectTrainingStats);
    }

    public SparkTrainingStats getSparkTrainingStats() {
        return trainingMaster.getTrainingStats();
    }

    /**
     * @return The trained ComputationGraph
     */
    public ComputationGraph getNetwork() {
        return network;
    }

    /**
     * @return The TrainingMaster for this network
     */
    public TrainingMaster getTrainingMaster() {
        return trainingMaster;
    }

    public void setNetwork(ComputationGraph network) {
        this.network = network;
    }

    /**
     * Fit the ComputationGraph with the given data set
     *
     * @param rdd Data to train on
     * @return Trained network
     */
    public ComputationGraph fit(RDD<DataSet> rdd) {
        return fit(rdd.toJavaRDD());
    }

    /**
     * Fit the ComputationGraph with the given data set
     *
     * @param rdd Data to train on
     * @return Trained network
     */
    public ComputationGraph fit(JavaRDD<DataSet> rdd) {
        if (Nd4j.getExecutioner() instanceof GridExecutioner)
            ((GridExecutioner) Nd4j.getExecutioner()).flushQueue();

        trainingMaster.executeTraining(this, rdd);
        return network;
    }

    /**
     * Fit the SparkComputationGraph network using a directory of serialized DataSet objects
     * The assumption here is that the directory contains a number of {@link DataSet} objects, each serialized using
     * {@link DataSet#save(OutputStream)}
     *
     * @param path Path to the directory containing the serialized DataSet objcets
     * @return The MultiLayerNetwork after training
     */
    public ComputationGraph fit(String path) {
        if (Nd4j.getExecutioner() instanceof GridExecutioner)
            ((GridExecutioner) Nd4j.getExecutioner()).flushQueue();

        JavaRDD<String> paths;
        try {
            paths = SparkUtils.listPaths(sc, path);
        } catch (IOException e) {
            throw new RuntimeException("Error listing paths in directory", e);
        }

        return fitPaths(paths);
    }

    /**
     * @deprecated Use {@link #fit(String)}
     */
    @Deprecated
    public ComputationGraph fit(String path, int minPartitions) {
        return fit(path);
    }

    /**
     * Fit the network using a list of paths for serialized DataSet objects.
     *
     * @param paths    List of paths
     * @return trained network
     */
    public ComputationGraph fitPaths(JavaRDD<String> paths) {
        trainingMaster.executeTrainingPaths(this, paths);
        return network;
    }

    /**
     * Fit the ComputationGraph with the given data set
     *
     * @param rdd Data to train on
     * @return Trained network
     */
    public ComputationGraph fitMultiDataSet(RDD<MultiDataSet> rdd) {
        return fitMultiDataSet(rdd.toJavaRDD());
    }

    /**
     * Fit the ComputationGraph with the given data set
     *
     * @param rdd Data to train on
     * @return Trained network
     */
    public ComputationGraph fitMultiDataSet(JavaRDD<MultiDataSet> rdd) {
        if (Nd4j.getExecutioner() instanceof GridExecutioner)
            ((GridExecutioner) Nd4j.getExecutioner()).flushQueue();

        trainingMaster.executeTrainingMDS(this, rdd);
        return network;
    }

    /**
     * Fit the SparkComputationGraph network using a directory of serialized MultiDataSet objects
     * The assumption here is that the directory contains a number of serialized {@link MultiDataSet} objects
     *
     * @param path Path to the directory containing the serialized MultiDataSet objcets
     * @return The MultiLayerNetwork after training
     */
    public ComputationGraph fitMultiDataSet(String path) {
        if (Nd4j.getExecutioner() instanceof GridExecutioner)
            ((GridExecutioner) Nd4j.getExecutioner()).flushQueue();

        JavaRDD<String> paths;
        try {
            paths = SparkUtils.listPaths(sc, path);
        } catch (IOException e) {
            throw new RuntimeException("Error listing paths in directory", e);
        }

        return fitPathsMultiDataSet(paths);
    }

    /**
     * Fit the network using a list of paths for serialized MultiDataSet objects.
     *
     * @param paths    List of paths
     * @return trained network
     */
    public ComputationGraph fitPathsMultiDataSet(JavaRDD<String> paths) {
        trainingMaster.executeTrainingPathsMDS(this, paths);
        return network;
    }

    /**
     * @deprecated use {@link #fitMultiDataSet(String)}
     */
    @Deprecated
    public ComputationGraph fitMultiDataSet(String path, int minPartitions) {
        return fitMultiDataSet(path);
    }

    /**
     * Gets the last (average) minibatch score from calling fit. This is the average score across all executors for the
     * last minibatch executed in each worker
     */
    public double getScore() {
        return lastScore;
    }

    public void setScore(double lastScore) {
        this.lastScore = lastScore;
    }

    /**
     * Calculate the score for all examples in the provided {@code JavaRDD<DataSet>}, either by summing
     * or averaging over the entire data set. To calculate a score for each example individually, use {@link #scoreExamples(JavaPairRDD, boolean)}
     * or one of the similar methods. Uses default minibatch size in each worker, {@link SparkComputationGraph#DEFAULT_EVAL_SCORE_BATCH_SIZE}
     *
     * @param data    Data to score
     * @param average Whether to sum the scores, or average them
     */
    public double calculateScore(JavaRDD<DataSet> data, boolean average) {
        return calculateScore(data, average, DEFAULT_EVAL_SCORE_BATCH_SIZE);
    }

    /**
     * Calculate the score for all examples in the provided {@code JavaRDD<DataSet>}, either by summing
     * or averaging over the entire data set. To calculate a score for each example individually, use {@link #scoreExamples(JavaPairRDD, boolean)}
     * or one of the similar methods
     *
     * @param data          Data to score
     * @param average       Whether to sum the scores, or average them
     * @param minibatchSize The number of examples to use in each minibatch when scoring. If more examples are in a partition than
     *                      this, multiple scoring operations will be done (to avoid using too much memory by doing the whole partition
     *                      in one go)
     */
    public double calculateScore(JavaRDD<DataSet> data, boolean average, int minibatchSize) {
        JavaRDD<Tuple2<Integer, Double>> rdd = data.mapPartitions(new ScoreFlatMapFunctionCGDataSet(conf.toJson(),
                        sc.broadcast(network.params(false)), minibatchSize));

        //Reduce to a single tuple, with example count + sum of scores
        Tuple2<Integer, Double> countAndSumScores = rdd.reduce(new IntDoubleReduceFunction());
        if (average) {
            return countAndSumScores._2() / countAndSumScores._1();
        } else {
            return countAndSumScores._2();
        }
    }

    /**
     * Calculate the score for all examples in the provided {@code JavaRDD<MultiDataSet>}, either by summing
     * or averaging over the entire data set.
     * Uses default minibatch size in each worker, {@link SparkComputationGraph#DEFAULT_EVAL_SCORE_BATCH_SIZE}
     *
     * @param data    Data to score
     * @param average Whether to sum the scores, or average them
     */
    public double calculateScoreMultiDataSet(JavaRDD<MultiDataSet> data, boolean average) {
        return calculateScoreMultiDataSet(data, average, DEFAULT_EVAL_SCORE_BATCH_SIZE);
    }

    /**
     * Calculate the score for all examples in the provided {@code JavaRDD<MultiDataSet>}, either by summing
     * or averaging over the entire data set.
     *      *
     * @param data          Data to score
     * @param average       Whether to sum the scores, or average them
     * @param minibatchSize The number of examples to use in each minibatch when scoring. If more examples are in a partition than
     *                      this, multiple scoring operations will be done (to avoid using too much memory by doing the whole partition
     *                      in one go)
     */
    public double calculateScoreMultiDataSet(JavaRDD<MultiDataSet> data, boolean average, int minibatchSize) {
        JavaRDD<Tuple2<Integer, Double>> rdd = data.mapPartitions(new ScoreFlatMapFunctionCGMultiDataSet(conf.toJson(),
                        sc.broadcast(network.params(false)), minibatchSize));
        //Reduce to a single tuple, with example count + sum of scores
        Tuple2<Integer, Double> countAndSumScores = rdd.reduce(new IntDoubleReduceFunction());
        if (average) {
            return countAndSumScores._2() / countAndSumScores._1();
        } else {
            return countAndSumScores._2();
        }
    }

    /**
     * DataSet version of {@link #scoreExamples(JavaRDD, boolean)}
     */
    public JavaDoubleRDD scoreExamples(JavaRDD<DataSet> data, boolean includeRegularizationTerms) {
        return scoreExamplesMultiDataSet(data.map(new DataSetToMultiDataSetFn()), includeRegularizationTerms);
    }

    /**
     * DataSet version of {@link #scoreExamples(JavaPairRDD, boolean, int)}
     */
    public JavaDoubleRDD scoreExamples(JavaRDD<DataSet> data, boolean includeRegularizationTerms, int batchSize) {
        return scoreExamplesMultiDataSet(data.map(new DataSetToMultiDataSetFn()), includeRegularizationTerms,
                        batchSize);
    }

    /**
     * DataSet version of {@link #scoreExamples(JavaPairRDD, boolean)}
     */
    public <K> JavaPairRDD<K, Double> scoreExamples(JavaPairRDD<K, DataSet> data, boolean includeRegularizationTerms) {
        return scoreExamplesMultiDataSet(data.mapToPair(new PairDataSetToMultiDataSetFn<K>()),
                        includeRegularizationTerms, DEFAULT_EVAL_SCORE_BATCH_SIZE);
    }

    /**
     * DataSet version of {@link #scoreExamples(JavaPairRDD, boolean, int)}
     */
    public <K> JavaPairRDD<K, Double> scoreExamples(JavaPairRDD<K, DataSet> data, boolean includeRegularizationTerms,
                    int batchSize) {
        return scoreExamplesMultiDataSet(data.mapToPair(new PairDataSetToMultiDataSetFn<K>()),
                        includeRegularizationTerms, batchSize);
    }

    /**
     * Score the examples individually, using the default batch size {@link #DEFAULT_EVAL_SCORE_BATCH_SIZE}. Unlike {@link #calculateScore(JavaRDD, boolean)},
     * this method returns a score for each example separately. If scoring is needed for specific examples use either
     * {@link #scoreExamples(JavaPairRDD, boolean)} or {@link #scoreExamples(JavaPairRDD, boolean, int)} which can have
     * a key for each example.
     *
     * @param data                       Data to score
     * @param includeRegularizationTerms If true: include the l1/l2 regularization terms with the score (if any)
     * @return A JavaDoubleRDD containing the scores of each example
     * @see ComputationGraph#scoreExamples(MultiDataSet, boolean)
     */
    public JavaDoubleRDD scoreExamplesMultiDataSet(JavaRDD<MultiDataSet> data, boolean includeRegularizationTerms) {
        return scoreExamplesMultiDataSet(data, includeRegularizationTerms, DEFAULT_EVAL_SCORE_BATCH_SIZE);
    }

    /**
     * Score the examples individually, using a specified batch size. Unlike {@link #calculateScore(JavaRDD, boolean)},
     * this method returns a score for each example separately. If scoring is needed for specific examples use either
     * {@link #scoreExamples(JavaPairRDD, boolean)} or {@link #scoreExamples(JavaPairRDD, boolean, int)} which can have
     * a key for each example.
     *
     * @param data                       Data to score
     * @param includeRegularizationTerms If true: include the l1/l2 regularization terms with the score (if any)
     * @param batchSize                  Batch size to use when doing scoring
     * @return A JavaDoubleRDD containing the scores of each example
     * @see ComputationGraph#scoreExamples(MultiDataSet, boolean)
     */
    public JavaDoubleRDD scoreExamplesMultiDataSet(JavaRDD<MultiDataSet> data, boolean includeRegularizationTerms,
                    int batchSize) {
        return data.mapPartitionsToDouble(new ScoreExamplesFunction(sc.broadcast(network.params()),
                        sc.broadcast(conf.toJson()), includeRegularizationTerms, batchSize));
    }

    /**
     * Score the examples individually, using the default batch size {@link #DEFAULT_EVAL_SCORE_BATCH_SIZE}. Unlike {@link #calculateScore(JavaRDD, boolean)},
     * this method returns a score for each example separately<br>
     * Note: The provided JavaPairRDD has a key that is associated with each example and returned score.<br>
     * <b>Note:</b> The DataSet objects passed in must have exactly one example in them (otherwise: can't have a 1:1 association
     * between keys and data sets to score)
     *
     * @param data                       Data to score
     * @param includeRegularizationTerms If true: include the l1/l2 regularization terms with the score (if any)
     * @param <K>                        Key type
     * @return A {@code JavaPairRDD<K,Double>} containing the scores of each example
     * @see MultiLayerNetwork#scoreExamples(DataSet, boolean)
     */
    public <K> JavaPairRDD<K, Double> scoreExamplesMultiDataSet(JavaPairRDD<K, MultiDataSet> data,
                    boolean includeRegularizationTerms) {
        return scoreExamplesMultiDataSet(data, includeRegularizationTerms, DEFAULT_EVAL_SCORE_BATCH_SIZE);
    }

    /**
     * Feed-forward the specified data, with the given keys. i.e., get the network output/predictions for the specified data
     *
     * @param featuresData Features data to feed through the network
     * @param batchSize    Batch size to use when doing feed forward operations
     * @param <K>          Type of data for key - may be anything
     * @return             Network output given the input, by key
     */
    public <K> JavaPairRDD<K, INDArray> feedForwardWithKeySingle(JavaPairRDD<K, INDArray> featuresData, int batchSize) {
        if (network.getNumInputArrays() != 1 || network.getNumOutputArrays() != 1) {
            throw new IllegalStateException(
                            "Cannot use this method with computation graphs with more than 1 input or output "
                                            + "( has: " + network.getNumInputArrays() + " inputs, "
                                            + network.getNumOutputArrays() + " outputs");
        }
        PairToArrayPair<K> p = new PairToArrayPair<K>();
        JavaPairRDD<K, INDArray[]> rdd = featuresData.mapToPair(p);
        return feedForwardWithKey(rdd, batchSize).mapToPair(new ArrayPairToPair<K>());
    }

    /**
     * Feed-forward the specified data, with the given keys. i.e., get the network output/predictions for the specified data
     *
     * @param featuresData Features data to feed through the network
     * @param batchSize    Batch size to use when doing feed forward operations
     * @param <K>          Type of data for key - may be anything
     * @return             Network output given the input, by key
     */
    public <K> JavaPairRDD<K, INDArray[]> feedForwardWithKey(JavaPairRDD<K, INDArray[]> featuresData, int batchSize) {
        return featuresData.mapPartitionsToPair(new GraphFeedForwardWithKeyFunction<K>(sc.broadcast(network.params()),
                        sc.broadcast(conf.toJson()), batchSize));
    }

    private void update(int mr, long mg) {
        Environment env = EnvironmentUtils.buildEnvironment();
        env.setNumCores(mr);
        env.setAvailableMemory(mg);
        Task task = ModelSerializer.taskByModel(network);
        Heartbeat.getInstance().reportEvent(Event.SPARK, env, task);
    }

    /**
     * Score the examples individually, using a specified batch size. Unlike {@link #calculateScore(JavaRDD, boolean)},
     * this method returns a score for each example separately<br>
     * Note: The provided JavaPairRDD has a key that is associated with each example and returned score.<br>
     * <b>Note:</b> The DataSet objects passed in must have exactly one example in them (otherwise: can't have a 1:1 association
     * between keys and data sets to score)
     *
     * @param data                       Data to score
     * @param includeRegularizationTerms If true: include the l1/l2 regularization terms with the score (if any)
     * @param <K>                        Key type
     * @return A {@code JavaPairRDD<K,Double>} containing the scores of each example
     * @see MultiLayerNetwork#scoreExamples(DataSet, boolean)
     */
    public <K> JavaPairRDD<K, Double> scoreExamplesMultiDataSet(JavaPairRDD<K, MultiDataSet> data,
                    boolean includeRegularizationTerms, int batchSize) {
        return data.mapPartitionsToPair(new ScoreExamplesWithKeyFunction<K>(sc.broadcast(network.params()),
                        sc.broadcast(conf.toJson()), includeRegularizationTerms, batchSize));
    }
}
