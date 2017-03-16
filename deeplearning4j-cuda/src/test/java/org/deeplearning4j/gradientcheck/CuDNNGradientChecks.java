package org.deeplearning4j.gradientcheck;

import org.deeplearning4j.datasets.iterator.impl.IrisDataSetIterator;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.distribution.NormalDistribution;
import org.deeplearning4j.nn.conf.distribution.UniformDistribution;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.layers.convolution.ConvolutionHelper;
import org.deeplearning4j.nn.layers.convolution.CudnnConvolutionHelper;
import org.deeplearning4j.nn.layers.normalization.BatchNormalizationHelper;
import org.deeplearning4j.nn.layers.normalization.CudnnBatchNormalizationHelper;
import org.deeplearning4j.nn.layers.normalization.CudnnLocalResponseNormalizationHelper;
import org.deeplearning4j.nn.layers.normalization.LocalResponseNormalizationHelper;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.junit.Test;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.buffer.util.DataTypeUtil;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.lang.reflect.Field;
import java.util.Random;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Created by Alex on 09/09/2016.
 */
public class CuDNNGradientChecks {

    private static final boolean PRINT_RESULTS = true;
    private static final boolean RETURN_ON_FIRST_FAILURE = false;
    private static final double DEFAULT_EPS = 1e-5;
    private static final double DEFAULT_MAX_REL_ERROR = 1e-2;
    private static final double DEFAULT_MIN_ABS_ERROR = 1e-6;

    static {
        DataTypeUtil.setDTypeForContext(DataBuffer.Type.DOUBLE);
    }


    @Test
    public void testConvolutional() throws Exception {

        //Parameterized test, testing combinations of:
        // (a) activation function
        // (b) Whether to test at random initialization, or after some learning (i.e., 'characteristic mode of operation')
        // (c) Loss function (with specified output activations)
        String[] activFns = {"sigmoid", "tanh"};
        boolean[] characteristic = {false, true};    //If true: run some backprop steps first

        int[] minibatchSizes = {1, 4};
        int width = 6;
        int height = 6;
        int inputDepth = 2;
        int nOut = 3;

        Field f = org.deeplearning4j.nn.layers.convolution.ConvolutionLayer.class.getDeclaredField("helper");
        f.setAccessible(true);

        Random r = new Random(12345);
        for (String afn : activFns) {
            for (boolean doLearningFirst : characteristic) {
                for (int minibatchSize : minibatchSizes) {

                    INDArray input = Nd4j.rand(new int[]{minibatchSize, inputDepth, height, width});
                    INDArray labels = Nd4j.zeros(minibatchSize, nOut);
                    for (int i = 0; i < minibatchSize; i++) {
                        labels.putScalar(i, r.nextInt(nOut), 1.0);
                    }

                    MultiLayerConfiguration.Builder builder = new NeuralNetConfiguration.Builder()
                            .regularization(false)
                            .optimizationAlgo(OptimizationAlgorithm.CONJUGATE_GRADIENT)
                            .weightInit(WeightInit.DISTRIBUTION).dist(new UniformDistribution(-1, 1))
                            .updater(Updater.NONE)
                            .seed(12345L)
                            .list()
                            .layer(0, new ConvolutionLayer.Builder(2, 2)
                                    .stride(2, 2)
                                    .padding(1, 1)
                                    .nOut(3)
                                    .activation(afn)
                                    .build())
                            .layer(1, new ConvolutionLayer.Builder(2, 2)
                                    .stride(2, 2)
                                    .padding(0, 0)
                                    .nOut(3)
                                    .activation(afn)
                                    .build())
                            .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                    .activation(Activation.SOFTMAX)
                                    .nOut(nOut)
                                    .build())
                            .setInputType(InputType.convolutional(height, width, inputDepth))
                            .pretrain(false).backprop(true);

                    MultiLayerConfiguration conf = builder.build();

                    MultiLayerNetwork mln = new MultiLayerNetwork(conf);
                    mln.init();

                    org.deeplearning4j.nn.layers.convolution.ConvolutionLayer c0
                            = (org.deeplearning4j.nn.layers.convolution.ConvolutionLayer) mln.getLayer(0);
                    ConvolutionHelper ch0 = (ConvolutionHelper) f.get(c0);
                    assertTrue(ch0 instanceof CudnnConvolutionHelper);

                    org.deeplearning4j.nn.layers.convolution.ConvolutionLayer c1
                            = (org.deeplearning4j.nn.layers.convolution.ConvolutionLayer) mln.getLayer(1);
                    ConvolutionHelper ch1 = (ConvolutionHelper) f.get(c1);
                    assertTrue(ch1 instanceof CudnnConvolutionHelper);

                    //-------------------------------
                    //For debugging/comparison to no-cudnn case: set helper field to null
//                    f.set(c0, null);
//                    f.set(c1, null);
//                    assertNull(f.get(c0));
//                    assertNull(f.get(c1));
                    //-------------------------------


                    String name = new Object() {
                    }.getClass().getEnclosingMethod().getName();

                    if (doLearningFirst) {
                        //Run a number of iterations of learning
                        mln.setInput(input);
                        mln.setLabels(labels);
                        mln.computeGradientAndScore();
                        double scoreBefore = mln.score();
                        for (int j = 0; j < 10; j++)
                            mln.fit(input, labels);
                        mln.computeGradientAndScore();
                        double scoreAfter = mln.score();
                        //Can't test in 'characteristic mode of operation' if not learning
                        String msg = name + " - score did not (sufficiently) decrease during learning - activationFn="
                                + afn + ", doLearningFirst= " + doLearningFirst
                                + " (before=" + scoreBefore + ", scoreAfter=" + scoreAfter + ")";
                        assertTrue(msg, scoreAfter < 0.8 * scoreBefore);
                    }

                    if (PRINT_RESULTS) {
                        System.out.println(name + " - activationFn=" + afn + ", doLearningFirst=" + doLearningFirst);
                        for (int j = 0; j < mln.getnLayers(); j++)
                            System.out.println("Layer " + j + " # params: " + mln.getLayer(j).numParams());
                    }

                    boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR, DEFAULT_MIN_ABS_ERROR,
                            PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                    assertTrue(gradOK);
                }
            }
        }
    }

    @Test
    public void testBatchNormCnn() throws Exception {
        //Note: CuDNN batch norm supports 4d only, as per 5.1 (according to api reference documentation)
        Nd4j.getRandom().setSeed(12345);
        int minibatch = 10;
        int depth = 1;
        int hw = 4;
        int nOut = 4;
        INDArray input = Nd4j.rand(new int[]{minibatch, depth, hw, hw});
        INDArray labels = Nd4j.zeros(minibatch, nOut);
        Random r = new Random(12345);
        for( int i=0; i<minibatch; i++ ){
            labels.putScalar(i,r.nextInt(nOut),1.0);
        }

        MultiLayerConfiguration.Builder builder = new NeuralNetConfiguration.Builder()
                .learningRate(1.0)
                .regularization(false)
                .updater(Updater.NONE)
                .seed(12345L)
                .weightInit(WeightInit.DISTRIBUTION).dist(new NormalDistribution(0, 2))
                .list()
                .layer(0, new ConvolutionLayer.Builder()
                        .kernelSize(2,2)
                        .stride(1,1)
                        .nIn(depth).nOut(2)
                        .activation(Activation.IDENTITY)
                        .build())
                .layer(1, new BatchNormalization.Builder().build())
                .layer(2, new ActivationLayer.Builder().activation(Activation.TANH).build())
                .layer(3, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                        .activation(Activation.SOFTMAX)
                        .nOut(nOut)
                        .build())
                .setInputType(InputType.convolutional(hw,hw,depth))
                .pretrain(false).backprop(true);

        MultiLayerNetwork mln = new MultiLayerNetwork(builder.build());
        mln.init();

        Field f = org.deeplearning4j.nn.layers.normalization.BatchNormalization.class.getDeclaredField("helper");
        f.setAccessible(true);

        org.deeplearning4j.nn.layers.normalization.BatchNormalization b
                = (org.deeplearning4j.nn.layers.normalization.BatchNormalization) mln.getLayer(1);
        BatchNormalizationHelper bn = (BatchNormalizationHelper) f.get(b);
        assertTrue(bn instanceof CudnnBatchNormalizationHelper);

        //-------------------------------
        //For debugging/comparison to no-cudnn case: set helper field to null
//        f.set(b, null);
//        assertNull(f.get(b));
        //-------------------------------

        if (PRINT_RESULTS) {
            for (int j = 0; j < mln.getnLayers(); j++)
                System.out.println("Layer " + j + " # params: " + mln.getLayer(j).numParams());
        }

        boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR, DEFAULT_MIN_ABS_ERROR,
                PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

        assertTrue(gradOK);
    }

    @Test
    public void testLRN() throws Exception {

        Nd4j.getRandom().setSeed(12345);
        int minibatch = 10;
        int depth = 6;
        int hw = 5;
        int nOut = 4;
        INDArray input = Nd4j.rand(new int[]{minibatch, depth, hw, hw});
        INDArray labels = Nd4j.zeros(minibatch, nOut);
        Random r = new Random(12345);
        for( int i=0; i<minibatch; i++ ){
            labels.putScalar(i,r.nextInt(nOut),1.0);
        }

        MultiLayerConfiguration.Builder builder = new NeuralNetConfiguration.Builder()
                .learningRate(1.0)
                .regularization(false)
                .updater(Updater.NONE)
                .seed(12345L)
                .weightInit(WeightInit.DISTRIBUTION).dist(new NormalDistribution(0, 2))
                .list()
                .layer(0, new ConvolutionLayer.Builder().nOut(6).kernelSize(2,2).stride(1,1).activation(Activation.TANH).build())
                .layer(1, new LocalResponseNormalization.Builder().build())
                .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                        .activation(Activation.SOFTMAX)
                        .nOut(nOut)
                        .build())
                .setInputType(InputType.convolutional(hw,hw,depth))
                .pretrain(false).backprop(true);

        MultiLayerNetwork mln = new MultiLayerNetwork(builder.build());
        mln.init();

        Field f = org.deeplearning4j.nn.layers.normalization.LocalResponseNormalization.class.getDeclaredField("helper");
        f.setAccessible(true);

        org.deeplearning4j.nn.layers.normalization.LocalResponseNormalization l
                = (org.deeplearning4j.nn.layers.normalization.LocalResponseNormalization) mln.getLayer(1);
        LocalResponseNormalizationHelper lrn = (LocalResponseNormalizationHelper) f.get(l);
        assertTrue(lrn instanceof CudnnLocalResponseNormalizationHelper);

        //-------------------------------
        //For debugging/comparison to no-cudnn case: set helper field to null
//        f.set(l, null);
//        assertNull(f.get(l));
        //-------------------------------

        if (PRINT_RESULTS) {
            for (int j = 0; j < mln.getnLayers(); j++)
                System.out.println("Layer " + j + " # params: " + mln.getLayer(j).numParams());
        }

        boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR, DEFAULT_MIN_ABS_ERROR,
                PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

        assertTrue(gradOK);
    }
}
