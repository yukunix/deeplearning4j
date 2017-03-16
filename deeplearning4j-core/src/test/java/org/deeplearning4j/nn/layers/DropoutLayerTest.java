package org.deeplearning4j.nn.layers;

import org.deeplearning4j.datasets.iterator.impl.MnistDataSetIterator;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.ConvolutionLayer;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.conf.layers.DropoutLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.junit.Test;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 */
public class DropoutLayerTest {

    @Test
    public void testInputTypes() {
        DropoutLayer config = new DropoutLayer.Builder(0.5).build();

        InputType in1 = InputType.feedForward(20);
        InputType in2 = InputType.convolutional(28, 28, 1);

        assertEquals(in1, config.getOutputType(0, in1));
        assertEquals(in2, config.getOutputType(0, in2));
        assertNull(config.getPreProcessorForInputType(in1));
        assertNull(config.getPreProcessorForInputType(in2));
    }

    @Test
    public void testDropoutLayerWithoutTraining() throws Exception {
        MultiLayerConfiguration confIntegrated = new NeuralNetConfiguration.Builder().seed(3648)
                        .list().layer(0,
                                        new ConvolutionLayer.Builder(1, 1).stride(1, 1).nIn(1).nOut(1).dropOut(0.25)
                                                        .activation(Activation.IDENTITY).weightInit(WeightInit.XAVIER)
                                                        .build())
                        .layer(1, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                        .weightInit(WeightInit.XAVIER).activation(Activation.IDENTITY).dropOut(0.25)
                                        .nOut(4).build())
                        .backprop(true).pretrain(false).setInputType(InputType.convolutionalFlat(2, 2, 1)).build();

        MultiLayerNetwork netIntegrated = new MultiLayerNetwork(confIntegrated);
        netIntegrated.init();
        netIntegrated.getLayer(0).setParam("W", Nd4j.eye(1));
        netIntegrated.getLayer(0).setParam("b", Nd4j.zeros(1, 1));
        netIntegrated.getLayer(1).setParam("W", Nd4j.eye(4));
        netIntegrated.getLayer(1).setParam("b", Nd4j.zeros(4, 1));

        MultiLayerConfiguration confSeparate =
                        new NeuralNetConfiguration.Builder()
                                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                                        .iterations(1).seed(3648)
                                        .list().layer(0,
                                                        new DropoutLayer.Builder(0.25)
                                                                        .build())
                                        .layer(1, new ConvolutionLayer.Builder(1, 1).stride(1, 1).nIn(1).nOut(1)
                                                        .activation(Activation.IDENTITY).weightInit(WeightInit.XAVIER)
                                                        .build())
                                        .layer(2, new DropoutLayer.Builder(0.25).build())
                                        .layer(3, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                                        .weightInit(WeightInit.XAVIER).activation(Activation.IDENTITY)
                                                        .nOut(4).build())
                                        .backprop(true).pretrain(false)
                                        .setInputType(InputType.convolutionalFlat(2, 2, 1)).build();

        MultiLayerNetwork netSeparate = new MultiLayerNetwork(confSeparate);
        netSeparate.init();
        netSeparate.getLayer(1).setParam("W", Nd4j.eye(1));
        netSeparate.getLayer(1).setParam("b", Nd4j.zeros(1, 1));
        netSeparate.getLayer(3).setParam("W", Nd4j.eye(4));
        netSeparate.getLayer(3).setParam("b", Nd4j.zeros(4, 1));

        INDArray in = Nd4j.arange(1, 5);
        Nd4j.getRandom().setSeed(12345);
        List<INDArray> actTrainIntegrated = netIntegrated.feedForward(in.dup(), true);
        Nd4j.getRandom().setSeed(12345);
        List<INDArray> actTrainSeparate = netSeparate.feedForward(in.dup(), true);
        Nd4j.getRandom().setSeed(12345);
        List<INDArray> actTestIntegrated = netIntegrated.feedForward(in.dup(), false);
        Nd4j.getRandom().setSeed(12345);
        List<INDArray> actTestSeparate = netSeparate.feedForward(in.dup(), false);

        assertEquals(actTrainIntegrated.get(1), actTrainSeparate.get(2));
        assertEquals(actTrainIntegrated.get(2), actTrainSeparate.get(4));
        assertEquals(actTestIntegrated.get(1), actTestSeparate.get(2));
        assertEquals(actTestIntegrated.get(2), actTestSeparate.get(4));
    }

    @Test
    public void testDropoutLayerWithDenseMnist() throws Exception {
        DataSetIterator iter = new MnistDataSetIterator(2, 2);
        DataSet next = iter.next();

        // Run without separate activation layer
        MultiLayerConfiguration confIntegrated = new NeuralNetConfiguration.Builder()
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).iterations(1).seed(123)
                        .list()
                        .layer(0, new DenseLayer.Builder().nIn(28 * 28 * 1).nOut(10)
                                        .activation(Activation.RELU).weightInit(
                                                        WeightInit.XAVIER)
                                        .build())
                        .layer(1, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                        .weightInit(WeightInit.XAVIER).activation(Activation.SOFTMAX).dropOut(0.25)
                                        .nIn(10).nOut(10).build())
                        .backprop(true).pretrain(false).build();

        MultiLayerNetwork netIntegrated = new MultiLayerNetwork(confIntegrated);
        netIntegrated.init();
        netIntegrated.fit(next);

        // Run with separate activation layer
        MultiLayerConfiguration confSeparate = new NeuralNetConfiguration.Builder()
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).iterations(1).seed(123)
                        .list()
                        .layer(0, new DenseLayer.Builder().nIn(28 * 28 * 1).nOut(10).activation(Activation.RELU)
                                        .weightInit(WeightInit.XAVIER).build())
                        .layer(1, new DropoutLayer.Builder(0.25).build())
                        .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                        .weightInit(WeightInit.XAVIER).activation(Activation.SOFTMAX).nIn(10).nOut(10)
                                        .build())
                        .backprop(true).pretrain(false).build();

        MultiLayerNetwork netSeparate = new MultiLayerNetwork(confSeparate);
        netSeparate.init();
        netSeparate.fit(next);

        // check parameters
        assertEquals(netIntegrated.getLayer(0).getParam("W"), netSeparate.getLayer(0).getParam("W"));
        assertEquals(netIntegrated.getLayer(0).getParam("b"), netSeparate.getLayer(0).getParam("b"));
        assertEquals(netIntegrated.getLayer(1).getParam("W"), netSeparate.getLayer(2).getParam("W"));
        assertEquals(netIntegrated.getLayer(1).getParam("b"), netSeparate.getLayer(2).getParam("b"));

        // check activations
        netIntegrated.setInput(next.getFeatureMatrix());
        netSeparate.setInput(next.getFeatureMatrix());

        Nd4j.getRandom().setSeed(12345);
        List<INDArray> actTrainIntegrated = netIntegrated.feedForward(true);
        Nd4j.getRandom().setSeed(12345);
        List<INDArray> actTrainSeparate = netSeparate.feedForward(true);
        assertEquals(actTrainIntegrated.get(1), actTrainSeparate.get(1));
        assertEquals(actTrainIntegrated.get(2), actTrainSeparate.get(3));

        Nd4j.getRandom().setSeed(12345);
        List<INDArray> actTestIntegrated = netIntegrated.feedForward(false);
        Nd4j.getRandom().setSeed(12345);
        List<INDArray> actTestSeparate = netSeparate.feedForward(false);
        assertEquals(actTestIntegrated.get(1), actTrainSeparate.get(1));
        assertEquals(actTestIntegrated.get(2), actTestSeparate.get(3));
    }

    @Test
    public void testDropoutLayerWithConvMnist() throws Exception {
        DataSetIterator iter = new MnistDataSetIterator(2, 2);
        DataSet next = iter.next();

        // Run without separate activation layer
        MultiLayerConfiguration confIntegrated =
                        new NeuralNetConfiguration.Builder()
                                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).iterations(
                                                        1)
                                        .seed(123).list()
                                        .layer(0, new ConvolutionLayer.Builder(4, 4).stride(2, 2).nIn(1).nOut(20)
                                                        .activation(Activation.RELU).weightInit(WeightInit.XAVIER)
                                                        .build())
                                        .layer(1, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                                        .weightInit(WeightInit.XAVIER).activation(Activation.SOFTMAX)
                                                        .dropOut(0.25).nOut(10).build())
                                        .backprop(true).pretrain(false)
                                        .setInputType(InputType.convolutionalFlat(28, 28, 1)).build();

        MultiLayerNetwork netIntegrated = new MultiLayerNetwork(confIntegrated);
        netIntegrated.init();
        netIntegrated.fit(next);

        // Run with separate activation layer
        MultiLayerConfiguration confSeparate =
                        new NeuralNetConfiguration.Builder()
                                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).iterations(
                                                        1)
                                        .seed(123).list()
                                        .layer(0, new ConvolutionLayer.Builder(4, 4).stride(2, 2).nIn(1).nOut(20)
                                                        .activation(Activation.RELU).weightInit(WeightInit.XAVIER)
                                                        .build())
                                        .layer(1, new DropoutLayer.Builder(0.25).build())
                                        .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                                        .weightInit(WeightInit.XAVIER).activation(Activation.SOFTMAX)
                                                        .nOut(10).build())
                                        .backprop(true).pretrain(false)
                                        .setInputType(InputType.convolutionalFlat(28, 28, 1)).build();

        MultiLayerNetwork netSeparate = new MultiLayerNetwork(confSeparate);
        netSeparate.init();
        netSeparate.fit(next);

        // check parameters
        assertEquals(netIntegrated.getLayer(0).getParam("W"), netSeparate.getLayer(0).getParam("W"));
        assertEquals(netIntegrated.getLayer(0).getParam("b"), netSeparate.getLayer(0).getParam("b"));
        assertEquals(netIntegrated.getLayer(1).getParam("W"), netSeparate.getLayer(2).getParam("W"));
        assertEquals(netIntegrated.getLayer(1).getParam("b"), netSeparate.getLayer(2).getParam("b"));

        // check activations
        netIntegrated.setInput(next.getFeatureMatrix());
        netSeparate.setInput(next.getFeatureMatrix());

        Nd4j.getRandom().setSeed(12345);
        List<INDArray> actTrainIntegrated = netIntegrated.feedForward(true);
        Nd4j.getRandom().setSeed(12345);
        List<INDArray> actTrainSeparate = netSeparate.feedForward(true);
        assertEquals(actTrainIntegrated.get(1), actTrainSeparate.get(1));
        assertEquals(actTrainIntegrated.get(2), actTrainSeparate.get(3));

        Nd4j.getRandom().setSeed(12345);
        List<INDArray> actTestIntegrated = netIntegrated.feedForward(false);
        Nd4j.getRandom().setSeed(12345);
        List<INDArray> actTestSeparate = netSeparate.feedForward(false);
        assertEquals(actTestIntegrated.get(1), actTrainSeparate.get(1));
        assertEquals(actTestIntegrated.get(2), actTestSeparate.get(3));
    }
}
