package org.deeplearning4j.regressiontest;

import org.deeplearning4j.nn.conf.ConvolutionMode;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.distribution.NormalDistribution;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.util.ModelSerializer;
import org.junit.Test;
import org.nd4j.linalg.activations.impl.ActivationLReLU;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.io.ClassPathResource;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.nd4j.linalg.lossfunctions.impl.LossMCXENT;
import org.nd4j.linalg.lossfunctions.impl.LossMSE;
import org.nd4j.linalg.lossfunctions.impl.LossNegativeLogLikelihood;

import java.io.File;

import static org.junit.Assert.*;

/**
 *
 * Regression tests for DL4J 0.5.0 - i.e., can we still load basic models generated in 0.5.0?
 * See dl4j-test-resources/src/main/resources/regression_testing/050/050_regression_test_readme.md
 *
 *
 * @author Alex Black
 */
public class RegressionTest050 {

    @Test
    public void regressionTestMLP1() throws Exception {

        File f = new ClassPathResource("regression_testing/050/050_ModelSerializer_Regression_MLP_1.zip")
                        .getTempFileFromArchive();

        MultiLayerNetwork net = ModelSerializer.restoreMultiLayerNetwork(f, true);

        MultiLayerConfiguration conf = net.getLayerWiseConfigurations();
        assertEquals(2, conf.getConfs().size());

        assertTrue(conf.isBackprop());
        assertFalse(conf.isPretrain());

        DenseLayer l0 = (DenseLayer) conf.getConf(0).getLayer();
        assertEquals("relu", l0.getActivationFn().toString());
        assertEquals(3, l0.getNIn());
        assertEquals(4, l0.getNOut());
        assertEquals(WeightInit.XAVIER, l0.getWeightInit());
        assertEquals(Updater.NESTEROVS, l0.getUpdater());
        assertEquals(0.9, l0.getMomentum(), 1e-6);
        assertEquals(0.15, l0.getLearningRate(), 1e-6);

        OutputLayer l1 = (OutputLayer) conf.getConf(1).getLayer();
        assertEquals("softmax", l1.getActivationFn().toString());
        assertEquals(LossFunctions.LossFunction.MCXENT, l1.getLossFunction());
        assertTrue(l1.getLossFn() instanceof LossMCXENT);
        assertEquals(4, l1.getNIn());
        assertEquals(5, l1.getNOut());
        assertEquals(WeightInit.XAVIER, l1.getWeightInit());
        assertEquals(Updater.NESTEROVS, l1.getUpdater());
        assertEquals(0.9, l1.getMomentum(), 1e-6);
        assertEquals(0.15, l1.getLearningRate(), 1e-6);

        int numParams = net.numParams();
        assertEquals(Nd4j.linspace(1, numParams, numParams), net.params());
        int updaterSize = net.getUpdater().stateSizeForLayer(net);
        assertEquals(Nd4j.linspace(1, updaterSize, updaterSize), net.getUpdater().getStateViewArray());
    }

    @Test
    public void regressionTestMLP2() throws Exception {

        File f = new ClassPathResource("regression_testing/050/050_ModelSerializer_Regression_MLP_2.zip")
                        .getTempFileFromArchive();

        MultiLayerNetwork net = ModelSerializer.restoreMultiLayerNetwork(f, true);

        MultiLayerConfiguration conf = net.getLayerWiseConfigurations();
        assertEquals(2, conf.getConfs().size());

        assertTrue(conf.isBackprop());
        assertFalse(conf.isPretrain());

        DenseLayer l0 = (DenseLayer) conf.getConf(0).getLayer();
        assertTrue(l0.getActivationFn() instanceof ActivationLReLU);
        assertEquals(3, l0.getNIn());
        assertEquals(4, l0.getNOut());
        assertEquals(WeightInit.DISTRIBUTION, l0.getWeightInit());
        assertEquals(new NormalDistribution(0.1, 1.2), l0.getDist());
        assertEquals(Updater.RMSPROP, l0.getUpdater());
        assertEquals(0.96, l0.getRmsDecay(), 1e-6);
        assertEquals(0.15, l0.getLearningRate(), 1e-6);
        assertEquals(0.6, l0.getDropOut(), 1e-6);
        assertEquals(0.1, l0.getL1(), 1e-6);
        assertEquals(0.2, l0.getL2(), 1e-6);

        OutputLayer l1 = (OutputLayer) conf.getConf(1).getLayer();
        assertEquals("identity", l1.getActivationFn().toString());
        assertEquals(LossFunctions.LossFunction.MSE, l1.getLossFunction());
        assertTrue(l1.getLossFn() instanceof LossMSE);
        assertEquals(4, l1.getNIn());
        assertEquals(5, l1.getNOut());
        assertEquals(WeightInit.DISTRIBUTION, l0.getWeightInit());
        assertEquals(new NormalDistribution(0.1, 1.2), l0.getDist());
        assertEquals(Updater.RMSPROP, l0.getUpdater());
        assertEquals(0.96, l1.getRmsDecay(), 1e-6);
        assertEquals(0.15, l1.getLearningRate(), 1e-6);
        assertEquals(0.6, l1.getDropOut(), 1e-6);
        assertEquals(0.1, l1.getL1(), 1e-6);
        assertEquals(0.2, l1.getL2(), 1e-6);

        int numParams = net.numParams();
        assertEquals(Nd4j.linspace(1, numParams, numParams), net.params());
        int updaterSize = net.getUpdater().stateSizeForLayer(net);
        assertEquals(Nd4j.linspace(1, updaterSize, updaterSize), net.getUpdater().getStateViewArray());
    }

    @Test
    public void regressionTestCNN1() throws Exception {

        File f = new ClassPathResource("regression_testing/050/050_ModelSerializer_Regression_CNN_1.zip")
                        .getTempFileFromArchive();

        MultiLayerNetwork net = ModelSerializer.restoreMultiLayerNetwork(f, true);

        MultiLayerConfiguration conf = net.getLayerWiseConfigurations();
        assertEquals(3, conf.getConfs().size());

        assertTrue(conf.isBackprop());
        assertFalse(conf.isPretrain());

        ConvolutionLayer l0 = (ConvolutionLayer) conf.getConf(0).getLayer();
        assertEquals("tanh", l0.getActivationFn().toString());
        assertEquals(3, l0.getNIn());
        assertEquals(3, l0.getNOut());
        assertEquals(WeightInit.RELU, l0.getWeightInit());
        assertEquals(Updater.RMSPROP, l0.getUpdater());
        assertEquals(0.96, l0.getRmsDecay(), 1e-6);
        assertEquals(0.15, l0.getLearningRate(), 1e-6);
        assertArrayEquals(new int[] {2, 2}, l0.getKernelSize());
        assertArrayEquals(new int[] {1, 1}, l0.getStride());
        assertArrayEquals(new int[] {0, 0}, l0.getPadding());
        assertEquals(l0.getConvolutionMode(), ConvolutionMode.Truncate); //Pre-0.7.0: no ConvolutionMode. Want to default to truncate here if not set

        SubsamplingLayer l1 = (SubsamplingLayer) conf.getConf(1).getLayer();
        assertArrayEquals(new int[] {2, 2}, l1.getKernelSize());
        assertArrayEquals(new int[] {1, 1}, l1.getStride());
        assertArrayEquals(new int[] {0, 0}, l1.getPadding());
        assertEquals(PoolingType.MAX, l1.getPoolingType());
        assertEquals(l1.getConvolutionMode(), ConvolutionMode.Truncate); //Pre-0.7.0: no ConvolutionMode. Want to default to truncate here if not set

        OutputLayer l2 = (OutputLayer) conf.getConf(2).getLayer();
        assertEquals("sigmoid", l1.getActivationFn().toString());
        assertEquals(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD, l2.getLossFunction());
        assertTrue(l2.getLossFn() instanceof LossNegativeLogLikelihood); //TODO
        assertEquals(26 * 26 * 3, l2.getNIn());
        assertEquals(5, l2.getNOut());
        assertEquals(WeightInit.RELU, l0.getWeightInit());
        assertEquals(Updater.RMSPROP, l0.getUpdater());
        assertEquals(0.96, l0.getRmsDecay(), 1e-6);
        assertEquals(0.15, l0.getLearningRate(), 1e-6);

        int numParams = net.numParams();
        assertEquals(Nd4j.linspace(1, numParams, numParams), net.params());
        int updaterSize = net.getUpdater().stateSizeForLayer(net);
        assertEquals(Nd4j.linspace(1, updaterSize, updaterSize), net.getUpdater().getStateViewArray());
    }
}
