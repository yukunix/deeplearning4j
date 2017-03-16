package org.deeplearning4j.nn.layers;

import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.transferlearning.FineTuneConfiguration;
import org.deeplearning4j.nn.transferlearning.TransferLearning;
import org.junit.Test;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Created by susaneraly on 2/5/17.
 */
@Slf4j
public class FrozenLayerTest {

    /*
        A model with a few frozen layers ==
            Model with non frozen layers set with the output of the forward pass of the frozen layers
     */
    @Test
    public void testFrozen() {
        DataSet randomData = new DataSet(Nd4j.rand(10, 4), Nd4j.rand(10, 3));

        NeuralNetConfiguration.Builder overallConf = new NeuralNetConfiguration.Builder().learningRate(0.1)
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).updater(Updater.SGD)
                        .activation(Activation.IDENTITY);

        FineTuneConfiguration finetune = new FineTuneConfiguration.Builder().learningRate(0.1).build();

        MultiLayerNetwork modelToFineTune = new MultiLayerNetwork(overallConf.clone().list()
                        .layer(0, new DenseLayer.Builder().nIn(4).nOut(3).build())
                        .layer(1, new DenseLayer.Builder().nIn(3).nOut(2).build())
                        .layer(2, new DenseLayer.Builder().nIn(2).nOut(3).build())
                        .layer(3, new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                        LossFunctions.LossFunction.MCXENT).activation(Activation.SOFTMAX).nIn(3).nOut(3)
                                                        .build())
                        .build());

        modelToFineTune.init();
        List<INDArray> ff = modelToFineTune.feedForwardToLayer(2, randomData.getFeatures(), false);
        INDArray asFrozenFeatures = ff.get(2);

        MultiLayerNetwork modelNow = new TransferLearning.Builder(modelToFineTune).fineTuneConfiguration(finetune)
                        .setFeatureExtractor(1).build();

        INDArray paramsLastTwoLayers =
                        Nd4j.hstack(modelToFineTune.getLayer(2).params(), modelToFineTune.getLayer(3).params());
        MultiLayerNetwork notFrozen = new MultiLayerNetwork(overallConf.clone().list()
                        .layer(0, new DenseLayer.Builder().nIn(2).nOut(3).build())
                        .layer(1, new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                        LossFunctions.LossFunction.MCXENT).activation(Activation.SOFTMAX).nIn(3).nOut(3)
                                                        .build())
                        .build(), paramsLastTwoLayers);

        //        assertEquals(modelNow.getLayer(2).conf(), notFrozen.getLayer(0).conf());  //Equal, other than names
        //        assertEquals(modelNow.getLayer(3).conf(), notFrozen.getLayer(1).conf());  //Equal, other than names

        //Check: forward pass
        INDArray outNow = modelNow.output(randomData.getFeatures());
        INDArray outNotFrozen = notFrozen.output(asFrozenFeatures);
        assertEquals(outNow, outNotFrozen);

        for (int i = 0; i < 5; i++) {
            notFrozen.fit(new DataSet(asFrozenFeatures, randomData.getLabels()));
            modelNow.fit(randomData);
        }

        INDArray expected = Nd4j.hstack(modelToFineTune.getLayer(0).params(), modelToFineTune.getLayer(1).params(),
                        notFrozen.params());
        INDArray act = modelNow.params();
        assertEquals(expected, act);
    }


    @Test
    public void cloneMLNFrozen() {

        DataSet randomData = new DataSet(Nd4j.rand(10, 4), Nd4j.rand(10, 3));

        NeuralNetConfiguration.Builder overallConf = new NeuralNetConfiguration.Builder().learningRate(0.1)
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).updater(Updater.SGD)
                        .activation(Activation.IDENTITY);
        MultiLayerNetwork modelToFineTune = new MultiLayerNetwork(overallConf.list()
                        .layer(0, new DenseLayer.Builder().nIn(4).nOut(3).build())
                        .layer(1, new DenseLayer.Builder().nIn(3).nOut(2).build())
                        .layer(2, new DenseLayer.Builder().nIn(2).nOut(3).build())
                        .layer(3, new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                        LossFunctions.LossFunction.MCXENT).activation(Activation.SOFTMAX).nIn(3).nOut(3)
                                                        .build())
                        .build());

        modelToFineTune.init();
        INDArray asFrozenFeatures = modelToFineTune.feedForwardToLayer(2, randomData.getFeatures(), false).get(2);
        MultiLayerNetwork modelNow = new TransferLearning.Builder(modelToFineTune).setFeatureExtractor(1).build();

        MultiLayerNetwork clonedModel = modelNow.clone();

        //Check json
        assertEquals(clonedModel.getLayerWiseConfigurations().toJson(), modelNow.getLayerWiseConfigurations().toJson());

        //Check params
        assertEquals(modelNow.params(), clonedModel.params());

        MultiLayerNetwork notFrozen = new MultiLayerNetwork(
                        overallConf.list().layer(0, new DenseLayer.Builder().nIn(2).nOut(3).build())
                                        .layer(1, new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                                        LossFunctions.LossFunction.MCXENT)
                                                                        .activation(Activation.SOFTMAX).nIn(3).nOut(3)
                                                                        .build())
                                        .build(),
                        Nd4j.hstack(modelToFineTune.getLayer(2).params(), modelToFineTune.getLayer(3).params()));

        int i = 0;
        while (i < 5) {
            notFrozen.fit(new DataSet(asFrozenFeatures, randomData.getLabels()));
            modelNow.fit(randomData);
            clonedModel.fit(randomData);
            i++;
        }

        INDArray expectedParams = Nd4j.hstack(modelToFineTune.getLayer(0).params(),
                        modelToFineTune.getLayer(1).params(), notFrozen.params());
        assertEquals(expectedParams, modelNow.params());
        assertEquals(expectedParams, clonedModel.params());

    }


    @Test
    public void testFrozenCompGraph() {
        DataSet randomData = new DataSet(Nd4j.rand(10, 4), Nd4j.rand(10, 3));

        NeuralNetConfiguration.Builder overallConf = new NeuralNetConfiguration.Builder().learningRate(0.1)
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).updater(Updater.SGD)
                        .activation(Activation.IDENTITY);

        ComputationGraph modelToFineTune = new ComputationGraph(overallConf.graphBuilder().addInputs("layer0In")
                        .addLayer("layer0", new DenseLayer.Builder().nIn(4).nOut(3).build(), "layer0In")
                        .addLayer("layer1", new DenseLayer.Builder().nIn(3).nOut(2).build(), "layer0")
                        .addLayer("layer2", new DenseLayer.Builder().nIn(2).nOut(3).build(), "layer1")
                        .addLayer("layer3",
                                        new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                                        LossFunctions.LossFunction.MCXENT)
                                                                        .activation(Activation.SOFTMAX).nIn(3).nOut(3)
                                                                        .build(),
                                        "layer2")
                        .setOutputs("layer3").build());

        modelToFineTune.init();
        INDArray asFrozenFeatures = modelToFineTune.feedForward(randomData.getFeatures(), false).get("layer1");

        ComputationGraph modelNow =
                        new TransferLearning.GraphBuilder(modelToFineTune).setFeatureExtractor("layer1").build();

        ComputationGraph notFrozen = new ComputationGraph(overallConf.graphBuilder().addInputs("layer0In")
                        .addLayer("layer0", new DenseLayer.Builder().nIn(2).nOut(3).build(), "layer0In")
                        .addLayer("layer1",
                                        new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                                        LossFunctions.LossFunction.MCXENT)
                                                                        .activation(Activation.SOFTMAX).nIn(3).nOut(3)
                                                                        .build(),
                                        "layer0")
                        .setOutputs("layer1").build());

        notFrozen.init();
        notFrozen.setParams(Nd4j.hstack(modelToFineTune.getLayer("layer2").params(),
                        modelToFineTune.getLayer("layer3").params()));

        int i = 0;
        while (i < 5) {
            notFrozen.fit(new DataSet(asFrozenFeatures, randomData.getLabels()));
            modelNow.fit(randomData);
            i++;
        }

        assertEquals(Nd4j.hstack(modelToFineTune.getLayer("layer0").params(),
                        modelToFineTune.getLayer("layer1").params(), notFrozen.params()), modelNow.params());
    }

    @Test
    public void cloneCompGraphFrozen() {

        DataSet randomData = new DataSet(Nd4j.rand(10, 4), Nd4j.rand(10, 3));

        NeuralNetConfiguration.Builder overallConf = new NeuralNetConfiguration.Builder().learningRate(0.1)
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).updater(Updater.SGD)
                        .activation(Activation.IDENTITY);

        ComputationGraph modelToFineTune = new ComputationGraph(overallConf.graphBuilder().addInputs("layer0In")
                        .addLayer("layer0", new DenseLayer.Builder().nIn(4).nOut(3).build(), "layer0In")
                        .addLayer("layer1", new DenseLayer.Builder().nIn(3).nOut(2).build(), "layer0")
                        .addLayer("layer2", new DenseLayer.Builder().nIn(2).nOut(3).build(), "layer1")
                        .addLayer("layer3",
                                        new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                                        LossFunctions.LossFunction.MCXENT)
                                                                        .activation(Activation.SOFTMAX).nIn(3).nOut(3)
                                                                        .build(),
                                        "layer2")
                        .setOutputs("layer3").build());

        modelToFineTune.init();
        INDArray asFrozenFeatures = modelToFineTune.feedForward(randomData.getFeatures(), false).get("layer1");
        ComputationGraph modelNow =
                        new TransferLearning.GraphBuilder(modelToFineTune).setFeatureExtractor("layer1").build();

        ComputationGraph clonedModel = modelNow.clone();

        //Check json
        assertEquals(clonedModel.getConfiguration().toJson(), modelNow.getConfiguration().toJson());

        //Check params
        assertEquals(modelNow.params(), clonedModel.params());

        ComputationGraph notFrozen = new ComputationGraph(overallConf.graphBuilder().addInputs("layer0In")
                        .addLayer("layer0", new DenseLayer.Builder().nIn(2).nOut(3).build(), "layer0In")
                        .addLayer("layer1",
                                        new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                                        LossFunctions.LossFunction.MCXENT)
                                                                        .activation(Activation.SOFTMAX).nIn(3).nOut(3)
                                                                        .build(),
                                        "layer0")
                        .setOutputs("layer1").build());
        notFrozen.init();
        notFrozen.setParams(Nd4j.hstack(modelToFineTune.getLayer("layer2").params(),
                        modelToFineTune.getLayer("layer3").params()));


        int i = 0;
        while (i < 5) {
            notFrozen.fit(new DataSet(asFrozenFeatures, randomData.getLabels()));
            modelNow.fit(randomData);
            clonedModel.fit(randomData);
            i++;
        }

        INDArray expectedParams = Nd4j.hstack(modelToFineTune.getLayer("layer0").params(),
                        modelToFineTune.getLayer("layer1").params(), notFrozen.params());
        assertEquals(expectedParams, modelNow.params());
        assertEquals(expectedParams, clonedModel.params());

    }
}
