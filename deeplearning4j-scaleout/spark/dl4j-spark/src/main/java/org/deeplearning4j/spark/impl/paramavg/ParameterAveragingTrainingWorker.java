package org.deeplearning4j.spark.impl.paramavg;

import org.apache.spark.broadcast.Broadcast;
import org.deeplearning4j.api.storage.Persistable;
import org.deeplearning4j.api.storage.StatsStorageRouter;
import org.deeplearning4j.api.storage.StatsStorageRouterProvider;
import org.deeplearning4j.api.storage.StorageMetaData;
import org.deeplearning4j.api.storage.listener.RoutingIterationListener;
import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.nn.api.Model;
import org.deeplearning4j.nn.api.Updater;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.graph.util.ComputationGraphUtil;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.updater.MultiLayerUpdater;
import org.deeplearning4j.nn.updater.graph.ComputationGraphUpdater;
import org.deeplearning4j.optimize.api.IterationListener;
import org.deeplearning4j.spark.api.TrainingHook;
import org.deeplearning4j.spark.api.TrainingWorker;
import org.deeplearning4j.spark.api.WorkerConfiguration;
import org.deeplearning4j.spark.api.stats.SparkTrainingStats;
import org.deeplearning4j.spark.api.worker.NetBroadcastTuple;
import org.deeplearning4j.spark.impl.listeners.VanillaStatsStorageRouter;
import org.deeplearning4j.spark.impl.paramavg.stats.ParameterAveragingTrainingWorkerStats;
import org.deeplearning4j.util.UIDProvider;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.executioner.GridExecutioner;
import org.nd4j.linalg.dataset.api.DataSet;
import org.nd4j.linalg.dataset.api.MultiDataSet;
import org.nd4j.linalg.factory.Nd4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * ParameterAveragingTrainingWorker
 * implements standard parameter
 * averaging every m iterations.
 *
 * @author Alex Black
 */
public class ParameterAveragingTrainingWorker implements TrainingWorker<ParameterAveragingTrainingResult> {

    private final Broadcast<NetBroadcastTuple> broadcast;
    private final boolean saveUpdater;
    private Collection<TrainingHook> trainingHooks;
    private final WorkerConfiguration configuration;
    private ParameterAveragingTrainingWorkerStats.ParameterAveragingTrainingWorkerStatsHelper stats = null;
    private Collection<IterationListener> iterationListeners;
    private StatsStorageRouterProvider listenerRouterProvider;

    public ParameterAveragingTrainingWorker(Broadcast<NetBroadcastTuple> broadcast, boolean saveUpdater,
                    WorkerConfiguration configuration, Collection<TrainingHook> trainingHooks,
                    Collection<IterationListener> listeners, StatsStorageRouterProvider routerProvider) {

        this.broadcast = broadcast;
        this.saveUpdater = saveUpdater;
        this.configuration = configuration;
        this.trainingHooks = trainingHooks;
        this.iterationListeners = listeners;
        this.listenerRouterProvider = routerProvider;
    }

    /**
     * Remove a training hook from the worker
     *
     * @param trainingHook the training hook to remove
     */
    @Override
    public void removeHook(TrainingHook trainingHook) {
        if (trainingHooks == null)
            return;
        trainingHooks.remove(trainingHook);
    }

    /**
     * Add a training hook to be used
     * during training of the worker
     *
     * @param trainingHook the training hook to add
     */
    @Override
    public void addHook(TrainingHook trainingHook) {
        if (trainingHooks == null)
            trainingHooks = new ArrayList<>();
        trainingHooks.add(trainingHook);
    }

    @Override
    public MultiLayerNetwork getInitialModel() {
        if (configuration.isCollectTrainingStats())
            stats = new ParameterAveragingTrainingWorkerStats.ParameterAveragingTrainingWorkerStatsHelper();

        if (configuration.isCollectTrainingStats())
            stats.logBroadcastGetValueStart();
        NetBroadcastTuple tuple = broadcast.getValue();
        if (configuration.isCollectTrainingStats())
            stats.logBroadcastGetValueEnd();

        //Don't want to have shared configuration object: each may update its iteration count (for LR schedule etc) individually
        MultiLayerNetwork net = new MultiLayerNetwork(tuple.getConfiguration().clone());
        //Can't have shared parameter array across executors for parameter averaging, hence the 'true' for clone parameters array arg
        net.init(tuple.getParameters().unsafeDuplication(), false);

        if (tuple.getUpdaterState() != null) {
            net.setUpdater(new MultiLayerUpdater(net, tuple.getUpdaterState().unsafeDuplication())); //Can't have shared updater state
        }

        if (Nd4j.getExecutioner() instanceof GridExecutioner)
            ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();

        configureListeners(net, tuple.getCounter().getAndIncrement());

        if (configuration.isCollectTrainingStats())
            stats.logInitEnd();

        return net;
    }

    @Override
    public ComputationGraph getInitialModelGraph() {
        if (configuration.isCollectTrainingStats())
            stats = new ParameterAveragingTrainingWorkerStats.ParameterAveragingTrainingWorkerStatsHelper();

        if (configuration.isCollectTrainingStats())
            stats.logBroadcastGetValueStart();
        NetBroadcastTuple tuple = broadcast.getValue();
        if (configuration.isCollectTrainingStats())
            stats.logBroadcastGetValueEnd();

        //Don't want to have shared configuration object: each may update its iteration count (for LR schedule etc) individually
        ComputationGraph net = new ComputationGraph(tuple.getGraphConfiguration().clone());
        //Can't have shared parameter array across executors for parameter averaging, hence the 'true' for clone parameters array arg
        net.init(tuple.getParameters().unsafeDuplication(), false);

        if (tuple.getUpdaterState() != null) {
            net.setUpdater(new ComputationGraphUpdater(net, tuple.getUpdaterState().unsafeDuplication())); //Again: can't have shared updater state
        }

        if (Nd4j.getExecutioner() instanceof GridExecutioner)
            ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();

        configureListeners(net, tuple.getCounter().getAndIncrement());

        if (configuration.isCollectTrainingStats())
            stats.logInitEnd();

        return net;
    }

    private void configureListeners(Model m, int counter) {
        if (iterationListeners != null) {
            List<IterationListener> list = new ArrayList<>(iterationListeners.size());
            for (IterationListener l : iterationListeners) {
                if (listenerRouterProvider != null && l instanceof RoutingIterationListener) {
                    RoutingIterationListener rl = (RoutingIterationListener) l;
                    rl.setStorageRouter(listenerRouterProvider.getRouter());
                    String workerID = UIDProvider.getJVMUID() + "_" + counter;
                    rl.setWorkerID(workerID);
                }
                list.add(l); //Don't need to clone listeners: not from broadcast, so deserialization handles
            }
            if (m instanceof MultiLayerNetwork)
                ((MultiLayerNetwork) m).setListeners(list);
            else
                ((ComputationGraph) m).setListeners(list);
        }
    }

    @Override
    public ParameterAveragingTrainingResult processMinibatch(DataSet dataSet, MultiLayerNetwork network,
                    boolean isLast) {
        if (configuration.isCollectTrainingStats())
            stats.logFitStart();

        if (trainingHooks != null) {
            for (TrainingHook trainingHook : trainingHooks) {
                trainingHook.preUpdate(dataSet, network);
            }
        }

        network.fit(dataSet);

        if (trainingHooks != null) {
            for (TrainingHook trainingHook : trainingHooks) {
                trainingHook.postUpdate(dataSet, network);
            }
        }


        if (configuration.isCollectTrainingStats())
            stats.logFitEnd(dataSet.numExamples());

        if (Nd4j.getExecutioner() instanceof GridExecutioner)
            ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();

        if (isLast)
            return getFinalResult(network);

        return null;
    }

    @Override
    public ParameterAveragingTrainingResult processMinibatch(DataSet dataSet, ComputationGraph graph, boolean isLast) {
        return processMinibatch(ComputationGraphUtil.toMultiDataSet(dataSet), graph, isLast);
    }

    @Override
    public ParameterAveragingTrainingResult processMinibatch(MultiDataSet dataSet, ComputationGraph graph,
                    boolean isLast) {
        if (configuration.isCollectTrainingStats())
            stats.logFitStart();
        //pre training hooks
        if (trainingHooks != null) {
            for (TrainingHook trainingHook : trainingHooks) {
                trainingHook.preUpdate(dataSet, graph);
            }
        }

        graph.fit(dataSet);

        //post training hooks
        if (trainingHooks != null) {
            for (TrainingHook trainingHook : trainingHooks) {
                trainingHook.postUpdate(dataSet, graph);
            }
        }


        if (configuration.isCollectTrainingStats())
            stats.logFitEnd(dataSet.getFeatures(0).size(0));

        if (Nd4j.getExecutioner() instanceof GridExecutioner)
            ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();

        if (isLast)
            return getFinalResult(graph);

        return null;
    }


    @Override
    public Pair<ParameterAveragingTrainingResult, SparkTrainingStats> processMinibatchWithStats(DataSet dataSet,
                    MultiLayerNetwork network, boolean isLast) {
        ParameterAveragingTrainingResult result = processMinibatch(dataSet, network, isLast);
        if (result == null)
            return null;

        SparkTrainingStats statsToReturn = (stats != null ? stats.build() : null);
        return new Pair<>(result, statsToReturn);
    }

    @Override
    public Pair<ParameterAveragingTrainingResult, SparkTrainingStats> processMinibatchWithStats(DataSet dataSet,
                    ComputationGraph graph, boolean isLast) {
        return processMinibatchWithStats(ComputationGraphUtil.toMultiDataSet(dataSet), graph, isLast);
    }

    @Override
    public Pair<ParameterAveragingTrainingResult, SparkTrainingStats> processMinibatchWithStats(MultiDataSet dataSet,
                    ComputationGraph graph, boolean isLast) {
        ParameterAveragingTrainingResult result = processMinibatch(dataSet, graph, isLast);
        if (result == null)
            return null;

        SparkTrainingStats statsToReturn = (stats != null ? stats.build() : null);
        return new Pair<>(result, statsToReturn);
    }

    @Override
    public ParameterAveragingTrainingResult getFinalResult(MultiLayerNetwork network) {
        INDArray updaterState = null;
        if (saveUpdater) {
            Updater u = network.getUpdater();
            if (u != null)
                updaterState = u.getStateViewArray();
        }

        if (Nd4j.getExecutioner() instanceof GridExecutioner)
            ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();

        Collection<StorageMetaData> storageMetaData = null;
        Collection<Persistable> listenerStaticInfo = null;
        Collection<Persistable> listenerUpdates = null;
        if (listenerRouterProvider != null) {
            StatsStorageRouter r = listenerRouterProvider.getRouter();
            if (r instanceof VanillaStatsStorageRouter) { //TODO this is ugly... need to find a better solution
                VanillaStatsStorageRouter ssr = (VanillaStatsStorageRouter) r;
                storageMetaData = ssr.getStorageMetaData();
                listenerStaticInfo = ssr.getStaticInfo();
                listenerUpdates = ssr.getUpdates();
            }
        }
        return new ParameterAveragingTrainingResult(network.params(), updaterState, network.score(), storageMetaData,
                        listenerStaticInfo, listenerUpdates);
    }

    @Override
    public ParameterAveragingTrainingResult getFinalResult(ComputationGraph network) {
        INDArray updaterState = null;
        if (saveUpdater) {
            ComputationGraphUpdater u = network.getUpdater();
            if (u != null)
                updaterState = u.getStateViewArray();
        }

        if (Nd4j.getExecutioner() instanceof GridExecutioner)
            ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();

        Collection<StorageMetaData> storageMetaData = null;
        Collection<Persistable> listenerStaticInfo = null;
        Collection<Persistable> listenerUpdates = null;
        if (listenerRouterProvider != null) {
            StatsStorageRouter r = listenerRouterProvider.getRouter();
            if (r instanceof VanillaStatsStorageRouter) { //TODO this is ugly... need to find a better solution
                VanillaStatsStorageRouter ssr = (VanillaStatsStorageRouter) r;
                storageMetaData = ssr.getStorageMetaData();
                listenerStaticInfo = ssr.getStaticInfo();
                listenerUpdates = ssr.getUpdates();
            }
        }

        return new ParameterAveragingTrainingResult(network.params(), updaterState, network.score(), storageMetaData,
                        listenerStaticInfo, listenerUpdates);
    }

    @Override
    public ParameterAveragingTrainingResult getFinalResultNoData() {
        return new ParameterAveragingTrainingResult(null, null, 0.0, null, null, null);
    }

    @Override
    public Pair<ParameterAveragingTrainingResult, SparkTrainingStats> getFinalResultNoDataWithStats() {
        return new Pair<>(getFinalResultNoData(), null);
    }

    @Override
    public Pair<ParameterAveragingTrainingResult, SparkTrainingStats> getFinalResultWithStats(
                    MultiLayerNetwork network) {
        ParameterAveragingTrainingResult result = getFinalResult(network);
        if (result == null)
            return null;

        SparkTrainingStats statsToReturn = (stats != null ? stats.build() : null);
        return new Pair<>(result, statsToReturn);
    }

    @Override
    public Pair<ParameterAveragingTrainingResult, SparkTrainingStats> getFinalResultWithStats(ComputationGraph graph) {
        ParameterAveragingTrainingResult result = getFinalResult(graph);
        if (result == null)
            return null;

        SparkTrainingStats statsToReturn = (stats != null ? stats.build() : null);
        return new Pair<>(result, statsToReturn);
    }

    @Override
    public WorkerConfiguration getDataConfiguration() {
        return configuration;
    }


}
