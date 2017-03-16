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
import org.deeplearning4j.nn.conf.preprocessor.RnnToCnnPreProcessor;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.junit.Test;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.activations.IActivation;
import org.nd4j.linalg.activations.impl.ActivationTanH;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.buffer.util.DataTypeUtil;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.dataset.api.preprocessor.DataNormalization;
import org.nd4j.linalg.dataset.api.preprocessor.NormalizerMinMaxScaler;
import org.nd4j.linalg.dataset.api.preprocessor.NormalizerStandardize;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.LossFunctions.LossFunction;

import java.util.Random;

import static org.junit.Assert.assertTrue;

/**
 * @author Alex Black 14 Aug 2015
 */
public class GradientCheckTests {

    private static final boolean PRINT_RESULTS = true;
    private static final boolean RETURN_ON_FIRST_FAILURE = false;
    private static final double DEFAULT_EPS = 1e-6;
    private static final double DEFAULT_MAX_REL_ERROR = 1e-3;
    private static final double DEFAULT_MIN_ABS_ERROR = 1e-8;

    static {
        //Force Nd4j initialization, then set data type to double:
        Nd4j.zeros(1);
        DataTypeUtil.setDTypeForContext(DataBuffer.Type.DOUBLE);
    }

    @Test
    public void testGradientMLP2LayerIrisSimple() {
        //Parameterized test, testing combinations of:
        // (a) activation function
        // (b) Whether to test at random initialization, or after some learning (i.e., 'characteristic mode of operation')
        // (c) Loss function (with specified output activations)
        String[] activFns = {"sigmoid", "tanh", "softplus"}; //activation functions such as relu and hardtanh: may randomly fail due to discontinuities
        boolean[] characteristic = {false, true}; //If true: run some backprop steps first

        LossFunction[] lossFunctions = {LossFunction.MCXENT, LossFunction.MSE};
        String[] outputActivations = {"softmax", "tanh"}; //i.e., lossFunctions[i] used with outputActivations[i] here
        DataNormalization scaler = new NormalizerMinMaxScaler();
        DataSetIterator iter = new IrisDataSetIterator(150, 150);
        scaler.fit(iter);
        iter.setPreProcessor(scaler);
        DataSet ds = iter.next();

        INDArray input = ds.getFeatureMatrix();
        INDArray labels = ds.getLabels();

        for (String afn : activFns) {
            for (boolean doLearningFirst : characteristic) {
                for (int i = 0; i < lossFunctions.length; i++) {
                    LossFunction lf = lossFunctions[i];
                    String outputActivation = outputActivations[i];

                    MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().regularization(false)
                                    .optimizationAlgo(OptimizationAlgorithm.CONJUGATE_GRADIENT).learningRate(1.0)
                                    .seed(12345L)
                                    .list().layer(0,
                                                    new DenseLayer.Builder().nIn(4).nOut(3)
                                                                    .weightInit(WeightInit.DISTRIBUTION)
                                                                    .dist(new NormalDistribution(0, 1))
                                                                    .activation(afn).updater(
                                                                                    Updater.SGD)
                                                                    .build())
                                    .layer(1, new OutputLayer.Builder(lf).activation(outputActivation).nIn(3).nOut(3)
                                                    .weightInit(WeightInit.DISTRIBUTION)
                                                    .dist(new NormalDistribution(0, 1)).updater(Updater.SGD).build())
                                    .pretrain(false).backprop(true).build();

                    MultiLayerNetwork mln = new MultiLayerNetwork(conf);
                    mln.init();

                    if (doLearningFirst) {
                        //Run a number of iterations of learning
                        mln.setInput(ds.getFeatures());
                        mln.setLabels(ds.getLabels());
                        mln.computeGradientAndScore();
                        double scoreBefore = mln.score();
                        for (int j = 0; j < 10; j++)
                            mln.fit(ds);
                        mln.computeGradientAndScore();
                        double scoreAfter = mln.score();
                        //Can't test in 'characteristic mode of operation' if not learning
                        String msg = "testGradMLP2LayerIrisSimple() - score did not (sufficiently) decrease during learning - activationFn="
                                        + afn + ", lossFn=" + lf + ", outputActivation=" + outputActivation
                                        + ", doLearningFirst=" + doLearningFirst + " (before=" + scoreBefore
                                        + ", scoreAfter=" + scoreAfter + ")";
                        assertTrue(msg, scoreAfter < 0.8 * scoreBefore);
                    }

                    if (PRINT_RESULTS) {
                        System.out.println("testGradientMLP2LayerIrisSimpleRandom() - activationFn=" + afn + ", lossFn="
                                        + lf + ", outputActivation=" + outputActivation + ", doLearningFirst="
                                        + doLearningFirst);
                        for (int j = 0; j < mln.getnLayers(); j++)
                            System.out.println("Layer " + j + " # params: " + mln.getLayer(j).numParams());
                    }

                    boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                    DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                    String msg = "testGradMLP2LayerIrisSimple() - activationFn=" + afn + ", lossFn=" + lf
                                    + ", outputActivation=" + outputActivation + ", doLearningFirst=" + doLearningFirst;
                    assertTrue(msg, gradOK);
                }
            }
        }
    }

    @Test
    public void testGradientMLP2LayerIrisL1L2Simple() {
        //As above (testGradientMLP2LayerIrisSimple()) but with L2, L1, and both L2/L1 applied
        //Need to run gradient through updater, so that L2 can be applied

        String[] activFns = {"sigmoid", "tanh"};
        boolean[] characteristic = {false, true}; //If true: run some backprop steps first

        LossFunction[] lossFunctions = {LossFunction.MCXENT, LossFunction.MSE};
        String[] outputActivations = {"softmax", "tanh"}; //i.e., lossFunctions[i] used with outputActivations[i] here

        DataNormalization scaler = new NormalizerMinMaxScaler();
        DataSetIterator iter = new IrisDataSetIterator(150, 150);
        scaler.fit(iter);
        iter.setPreProcessor(scaler);
        DataSet ds = iter.next();

        INDArray input = ds.getFeatureMatrix();
        INDArray labels = ds.getLabels();

        //use l2vals[i] with l1vals[i]
        double[] l2vals = {0.4, 0.0, 0.4, 0.4};
        double[] l1vals = {0.0, 0.0, 0.5, 0.0};
        double[] biasL2 = {0.0, 0.0, 0.0, 0.2};
        double[] biasL1 = {0.0, 0.0, 0.6, 0.0};

        for (String afn : activFns) {
            for (boolean doLearningFirst : characteristic) {
                for (int i = 0; i < lossFunctions.length; i++) {
                    for (int k = 0; k < l2vals.length; k++) {
                        LossFunction lf = lossFunctions[i];
                        String outputActivation = outputActivations[i];
                        double l2 = l2vals[k];
                        double l1 = l1vals[k];

                        MultiLayerConfiguration conf =
                                        new NeuralNetConfiguration.Builder().regularization(true).l2(l2).l1(l1)
                                                        .l2Bias(biasL2[k]).l1Bias(biasL1[k])
                                                        .optimizationAlgo(OptimizationAlgorithm.CONJUGATE_GRADIENT)
                                                        .seed(12345L)
                                                        .list().layer(0,
                                                                        new DenseLayer.Builder().nIn(4).nOut(3)
                                                                                        .weightInit(WeightInit.DISTRIBUTION)
                                                                                        .dist(new NormalDistribution(0,
                                                                                                        1))
                                                                                        .updater(Updater.NONE)
                                                                                        .activation(afn).build())
                                                        .layer(1, new OutputLayer.Builder(lf).nIn(3).nOut(3)
                                                                        .weightInit(WeightInit.DISTRIBUTION)
                                                                        .dist(new NormalDistribution(0, 1))
                                                                        .updater(Updater.NONE)
                                                                        .activation(outputActivation).build())
                                                        .pretrain(false).backprop(true).build();

                        MultiLayerNetwork mln = new MultiLayerNetwork(conf);
                        mln.init();

                        if (doLearningFirst) {
                            //Run a number of iterations of learning
                            mln.setInput(ds.getFeatures());
                            mln.setLabels(ds.getLabels());
                            mln.computeGradientAndScore();
                            double scoreBefore = mln.score();
                            for (int j = 0; j < 10; j++)
                                mln.fit(ds);
                            mln.computeGradientAndScore();
                            double scoreAfter = mln.score();
                            //Can't test in 'characteristic mode of operation' if not learning
                            String msg = "testGradMLP2LayerIrisSimple() - score did not (sufficiently) decrease during learning - activationFn="
                                            + afn + ", lossFn=" + lf + ", outputActivation=" + outputActivation
                                            + ", doLearningFirst=" + doLearningFirst + ", l2=" + l2 + ", l1=" + l1
                                            + " (before=" + scoreBefore + ", scoreAfter=" + scoreAfter + ")";
                            assertTrue(msg, scoreAfter < 0.8 * scoreBefore);
                        }

                        if (PRINT_RESULTS) {
                            System.out.println("testGradientMLP2LayerIrisSimpleRandom() - activationFn=" + afn
                                            + ", lossFn=" + lf + ", outputActivation=" + outputActivation
                                            + ", doLearningFirst=" + doLearningFirst + ", l2=" + l2 + ", l1=" + l1);
                            for (int j = 0; j < mln.getnLayers(); j++)
                                System.out.println("Layer " + j + " # params: " + mln.getLayer(j).numParams());
                        }

                        boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                        DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                        String msg = "testGradMLP2LayerIrisSimple() - activationFn=" + afn + ", lossFn=" + lf
                                        + ", outputActivation=" + outputActivation + ", doLearningFirst="
                                        + doLearningFirst + ", l2=" + l2 + ", l1=" + l1;
                        assertTrue(msg, gradOK);
                    }
                }
            }
        }
    }

    @Test
    public void testEmbeddingLayerSimple() {
        Random r = new Random(12345);
        int nExamples = 5;
        INDArray input = Nd4j.zeros(nExamples, 1);
        INDArray labels = Nd4j.zeros(nExamples, 3);
        for (int i = 0; i < nExamples; i++) {
            input.putScalar(i, r.nextInt(4));
            labels.putScalar(new int[] {i, r.nextInt(3)}, 1.0);
        }

        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().regularization(true).l2(0.2).l1(0.1)
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).seed(12345L)
                        .list().layer(0,
                                        new EmbeddingLayer.Builder().nIn(4).nOut(3).weightInit(WeightInit.XAVIER)
                                                        .dist(new NormalDistribution(0, 1))
                                                        .updater(Updater.NONE).activation(
                                                                        Activation.TANH)
                                                        .build())
                        .layer(1, new OutputLayer.Builder(LossFunction.MCXENT).nIn(3).nOut(3)
                                        .weightInit(WeightInit.XAVIER).dist(new NormalDistribution(0, 1))
                                        .updater(Updater.NONE).activation(Activation.SOFTMAX).build())
                        .pretrain(false).backprop(true).build();

        MultiLayerNetwork mln = new MultiLayerNetwork(conf);
        mln.init();

        if (PRINT_RESULTS) {
            System.out.println("testEmbeddingLayerSimple");
            for (int j = 0; j < mln.getnLayers(); j++)
                System.out.println("Layer " + j + " # params: " + mln.getLayer(j).numParams());
        }

        boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                        DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

        String msg = "testEmbeddingLayerSimple";
        assertTrue(msg, gradOK);
    }

    @Test
    public void testGravesLSTMBasicMultiLayer() {
        //Basic test of GravesLSTM layer
        Nd4j.getRandom().setSeed(12345L);

        int timeSeriesLength = 4;
        int nIn = 2;
        int layerSize = 2;
        int nOut = 2;
        int miniBatchSize = 5;

        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().regularization(false).seed(12345L).list()
                        .layer(0, new GravesLSTM.Builder().nIn(nIn).nOut(layerSize).activation(Activation.SIGMOID)
                                        .weightInit(WeightInit.DISTRIBUTION).dist(new NormalDistribution(0, 1.0))
                                        .updater(Updater.NONE).build())
                        .layer(1, new GravesLSTM.Builder().nIn(layerSize).nOut(layerSize).activation(Activation.SIGMOID)
                                        .weightInit(WeightInit.DISTRIBUTION).dist(
                                                        new NormalDistribution(0, 1.0))
                                        .updater(Updater.NONE).build())
                        .layer(2, new RnnOutputLayer.Builder(LossFunction.MCXENT).activation(Activation.SOFTMAX)
                                        .nIn(layerSize).nOut(nOut).weightInit(WeightInit.DISTRIBUTION)
                                        .dist(new NormalDistribution(0, 1.0)).updater(Updater.NONE).build())
                        .pretrain(false).backprop(true).build();

        MultiLayerNetwork mln = new MultiLayerNetwork(conf);
        mln.init();

        Random r = new Random(12345L);
        INDArray input = Nd4j.zeros(miniBatchSize, nIn, timeSeriesLength);
        for (int i = 0; i < miniBatchSize; i++) {
            for (int j = 0; j < nIn; j++) {
                for (int k = 0; k < timeSeriesLength; k++) {
                    input.putScalar(new int[] {i, j, k}, r.nextDouble() - 0.5);
                }
            }
        }

        INDArray labels = Nd4j.zeros(miniBatchSize, nOut, timeSeriesLength);
        for (int i = 0; i < miniBatchSize; i++) {
            for (int j = 0; j < timeSeriesLength; j++) {
                int idx = r.nextInt(nOut);
                labels.putScalar(new int[] {i, idx, j}, 1.0);
            }
        }

        if (PRINT_RESULTS) {
            System.out.println("testGravesLSTMBasic()");
            for (int j = 0; j < mln.getnLayers(); j++)
                System.out.println("Layer " + j + " # params: " + mln.getLayer(j).numParams());
        }

        boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                        DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

        assertTrue(gradOK);
    }

    @Test
    public void testGradientGravesLSTMFull() {
        String[] activFns = {"tanh", "softsign"};

        LossFunction[] lossFunctions = {LossFunction.MCXENT, LossFunction.MSE};
        String[] outputActivations = {"softmax", "tanh"}; //i.e., lossFunctions[i] used with outputActivations[i] here

        int timeSeriesLength = 8;
        int nIn = 7;
        int layerSize = 9;
        int nOut = 4;
        int miniBatchSize = 6;

        Random r = new Random(12345L);
        INDArray input = Nd4j.zeros(miniBatchSize, nIn, timeSeriesLength);
        for (int i = 0; i < miniBatchSize; i++) {
            for (int j = 0; j < nIn; j++) {
                for (int k = 0; k < timeSeriesLength; k++) {
                    input.putScalar(new int[] {i, j, k}, r.nextDouble() - 0.5);
                }
            }
        }

        INDArray labels = Nd4j.zeros(miniBatchSize, nOut, timeSeriesLength);
        for (int i = 0; i < miniBatchSize; i++) {
            for (int j = 0; j < timeSeriesLength; j++) {
                int idx = r.nextInt(nOut);
                labels.putScalar(new int[] {i, idx, j}, 1.0f);
            }
        }


        //use l2vals[i] with l1vals[i]
        double[] l2vals = {0.4, 0.0, 0.4, 0.4};
        double[] l1vals = {0.0, 0.0, 0.5, 0.0};
        double[] biasL2 = {0.0, 0.0, 0.0, 0.2};
        double[] biasL1 = {0.0, 0.0, 0.6, 0.0};

        for (String afn : activFns) {
            for (int i = 0; i < lossFunctions.length; i++) {
                for (int k = 0; k < l2vals.length; k++) {
                    LossFunction lf = lossFunctions[i];
                    String outputActivation = outputActivations[i];
                    double l2 = l2vals[k];
                    double l1 = l1vals[k];

                    NeuralNetConfiguration.Builder conf = new NeuralNetConfiguration.Builder()
                                    .regularization(l1 > 0.0 || l2 > 0.0).seed(12345L);
                    if (l1 > 0.0)
                        conf.l1(l1);
                    if (l2 > 0.0)
                        conf.l2(l2);
                    if (biasL2[k] > 0)
                        conf.l2Bias(biasL2[k]);
                    if (biasL1[k] > 0)
                        conf.l1Bias(biasL1[k]);
                    NeuralNetConfiguration.ListBuilder conf2 = conf
                                    .list().layer(0,
                                                    new GravesLSTM.Builder().nIn(nIn).nOut(layerSize)
                                                                    .weightInit(WeightInit.DISTRIBUTION)
                                                                    .dist(new NormalDistribution(0, 1))
                                                                    .activation(afn).updater(
                                                                                    Updater.NONE)
                                                                    .build())
                                    .layer(1, new RnnOutputLayer.Builder(lf).activation(outputActivation).nIn(layerSize)
                                                    .nOut(nOut).weightInit(WeightInit.DISTRIBUTION)
                                                    .dist(new NormalDistribution(0, 1)).updater(Updater.NONE).build())
                                    .pretrain(false).backprop(true);

                    MultiLayerNetwork mln = new MultiLayerNetwork(conf2.build());
                    mln.init();

                    if (PRINT_RESULTS) {
                        System.out.println("testGradientGravesLSTMFull() - activationFn=" + afn + ", lossFn=" + lf
                                        + ", outputActivation=" + outputActivation + ", l2=" + l2 + ", l1=" + l1);
                        for (int j = 0; j < mln.getnLayers(); j++)
                            System.out.println("Layer " + j + " # params: " + mln.getLayer(j).numParams());
                    }

                    boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                    DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                    String msg = "testGradientGravesLSTMFull() - activationFn=" + afn + ", lossFn=" + lf
                                    + ", outputActivation=" + outputActivation + ", l2=" + l2 + ", l1=" + l1;
                    assertTrue(msg, gradOK);
                }
            }
        }
    }


    @Test
    public void testGradientGravesLSTMEdgeCases() {
        //Edge cases: T=1, miniBatchSize=1, both
        int[] timeSeriesLength = {1, 5, 1};
        int[] miniBatchSize = {7, 1, 1};

        int nIn = 7;
        int layerSize = 9;
        int nOut = 4;

        for (int i = 0; i < timeSeriesLength.length; i++) {

            Random r = new Random(12345L);
            INDArray input = Nd4j.zeros(miniBatchSize[i], nIn, timeSeriesLength[i]);
            for (int m = 0; m < miniBatchSize[i]; m++) {
                for (int j = 0; j < nIn; j++) {
                    for (int k = 0; k < timeSeriesLength[i]; k++) {
                        input.putScalar(new int[] {m, j, k}, r.nextDouble() - 0.5);
                    }
                }
            }

            INDArray labels = Nd4j.zeros(miniBatchSize[i], nOut, timeSeriesLength[i]);
            for (int m = 0; m < miniBatchSize[i]; m++) {
                for (int j = 0; j < timeSeriesLength[i]; j++) {
                    int idx = r.nextInt(nOut);
                    labels.putScalar(new int[] {m, idx, j}, 1.0f);
                }
            }

            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().regularization(false).seed(12345L)
                            .list()
                            .layer(0, new GravesLSTM.Builder().nIn(nIn).nOut(layerSize)
                                            .weightInit(WeightInit.DISTRIBUTION)
                                            .dist(new NormalDistribution(0, 1)).updater(
                                                            Updater.NONE)
                                            .build())
                            .layer(1, new RnnOutputLayer.Builder(LossFunction.MCXENT).activation(Activation.SOFTMAX)
                                            .nIn(layerSize).nOut(nOut).weightInit(WeightInit.DISTRIBUTION)
                                            .dist(new NormalDistribution(0, 1)).updater(Updater.NONE).build())
                            .pretrain(false).backprop(true).build();
            MultiLayerNetwork mln = new MultiLayerNetwork(conf);
            mln.init();

            boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                            DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

            String msg = "testGradientGravesLSTMEdgeCases() - timeSeriesLength=" + timeSeriesLength[i]
                            + ", miniBatchSize=" + miniBatchSize[i];
            assertTrue(msg, gradOK);
        }
    }

    @Test
    public void testGradientGravesBidirectionalLSTMFull() {
        Activation[] activFns = {Activation.TANH, Activation.SOFTSIGN};

        LossFunction[] lossFunctions = {LossFunction.MCXENT, LossFunction.MSE};
        Activation[] outputActivations = {Activation.SOFTMAX, Activation.TANH}; //i.e., lossFunctions[i] used with outputActivations[i] here

        int timeSeriesLength = 4;
        int nIn = 2;
        int layerSize = 2;
        int nOut = 2;
        int miniBatchSize = 3;

        Random r = new Random(12345L);
        INDArray input = Nd4j.zeros(miniBatchSize, nIn, timeSeriesLength);
        for (int i = 0; i < miniBatchSize; i++) {
            for (int j = 0; j < nIn; j++) {
                for (int k = 0; k < timeSeriesLength; k++) {
                    input.putScalar(new int[] {i, j, k}, r.nextDouble() - 0.5);
                }
            }
        }

        INDArray labels = Nd4j.zeros(miniBatchSize, nOut, timeSeriesLength);
        for (int i = 0; i < miniBatchSize; i++) {
            for (int j = 0; j < timeSeriesLength; j++) {
                int idx = r.nextInt(nOut);
                labels.putScalar(new int[] {i, idx, j}, 1.0f);
            }
        }


        //use l2vals[i] with l1vals[i]
        double[] l2vals = {0.4, 0.0, 0.4, 0.4};
        double[] l1vals = {0.0, 0.0, 0.5, 0.0};
        double[] biasL2 = {0.0, 0.0, 0.0, 0.2};
        double[] biasL1 = {0.0, 0.0, 0.6, 0.0};

        for (Activation afn : activFns) {
            for (int i = 0; i < lossFunctions.length; i++) {
                for (int k = 0; k < l2vals.length; k++) {
                    LossFunction lf = lossFunctions[i];
                    Activation outputActivation = outputActivations[i];
                    double l2 = l2vals[k];
                    double l1 = l1vals[k];

                    NeuralNetConfiguration.Builder conf =
                                    new NeuralNetConfiguration.Builder().regularization(l1 > 0.0 || l2 > 0.0);
                    if (l1 > 0.0)
                        conf.l1(l1);
                    if (l2 > 0.0)
                        conf.l2(l2);
                    if (biasL2[k] > 0)
                        conf.l2Bias(biasL2[k]);
                    if (biasL1[k] > 0)
                        conf.l1Bias(biasL1[k]);

                    MultiLayerConfiguration mlc = conf.seed(12345L)
                                    .list().layer(0,
                                                    new GravesBidirectionalLSTM.Builder().nIn(nIn).nOut(layerSize)
                                                                    .weightInit(WeightInit.DISTRIBUTION)
                                                                    .dist(new NormalDistribution(0, 1))
                                                                    .activation(afn).updater(
                                                                                    Updater.NONE)
                                                                    .build())
                                    .layer(1, new RnnOutputLayer.Builder(lf).activation(outputActivation).nIn(layerSize)
                                                    .nOut(nOut).weightInit(WeightInit.DISTRIBUTION)
                                                    .dist(new NormalDistribution(0, 1)).updater(Updater.NONE).build())
                                    .pretrain(false).backprop(true).build();


                    MultiLayerNetwork mln = new MultiLayerNetwork(mlc);

                    mln.init();

                    if (PRINT_RESULTS) {
                        System.out.println("testGradientGravesBidirectionalLSTMFull() - activationFn=" + afn
                                        + ", lossFn=" + lf + ", outputActivation=" + outputActivation + ", l2=" + l2
                                        + ", l1=" + l1);
                        for (int j = 0; j < mln.getnLayers(); j++)
                            System.out.println("Layer " + j + " # params: " + mln.getLayer(j).numParams());
                    }

                    boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                    DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                    String msg = "testGradientGravesLSTMFull() - activationFn=" + afn + ", lossFn=" + lf
                                    + ", outputActivation=" + outputActivation + ", l2=" + l2 + ", l1=" + l1;
                    assertTrue(msg, gradOK);
                }
            }
        }
    }

    @Test
    public void testGradientGravesBidirectionalLSTMEdgeCases() {
        //Edge cases: T=1, miniBatchSize=1, both
        int[] timeSeriesLength = {1, 5, 1};
        int[] miniBatchSize = {7, 1, 1};

        int nIn = 7;
        int layerSize = 9;
        int nOut = 4;

        for (int i = 0; i < timeSeriesLength.length; i++) {

            Random r = new Random(12345L);
            INDArray input = Nd4j.zeros(miniBatchSize[i], nIn, timeSeriesLength[i]);
            for (int m = 0; m < miniBatchSize[i]; m++) {
                for (int j = 0; j < nIn; j++) {
                    for (int k = 0; k < timeSeriesLength[i]; k++) {
                        input.putScalar(new int[] {m, j, k}, r.nextDouble() - 0.5);
                    }
                }
            }

            INDArray labels = Nd4j.zeros(miniBatchSize[i], nOut, timeSeriesLength[i]);
            for (int m = 0; m < miniBatchSize[i]; m++) {
                for (int j = 0; j < timeSeriesLength[i]; j++) {
                    int idx = r.nextInt(nOut);
                    labels.putScalar(new int[] {m, idx, j}, 1.0f);
                }
            }

            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().regularization(false).seed(12345L)
                            .list()
                            .layer(0, new GravesBidirectionalLSTM.Builder().nIn(nIn).nOut(layerSize)
                                            .weightInit(WeightInit.DISTRIBUTION)
                                            .dist(new NormalDistribution(0, 1)).updater(
                                                            Updater.NONE)
                                            .build())
                            .layer(1, new RnnOutputLayer.Builder(LossFunction.MCXENT).activation(Activation.SOFTMAX)
                                            .nIn(layerSize).nOut(nOut).weightInit(WeightInit.DISTRIBUTION)
                                            .dist(new NormalDistribution(0, 1)).updater(Updater.NONE).build())
                            .pretrain(false).backprop(true).build();
            MultiLayerNetwork mln = new MultiLayerNetwork(conf);
            mln.init();

            boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                            DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

            String msg = "testGradientGravesLSTMEdgeCases() - timeSeriesLength=" + timeSeriesLength[i]
                            + ", miniBatchSize=" + miniBatchSize[i];
            assertTrue(msg, gradOK);
        }
    }

    @Test
    public void testGradientCnnFfRnn() {
        //Test gradients with CNN -> FF -> LSTM -> RnnOutputLayer
        //time series input/output (i.e., video classification or similar)

        int nChannelsIn = 3;
        int inputSize = 10 * 10 * nChannelsIn; //10px x 10px x 3 channels
        int miniBatchSize = 4;
        int timeSeriesLength = 10;
        int nClasses = 3;

        //Generate
        Nd4j.getRandom().setSeed(12345);
        INDArray input = Nd4j.rand(new int[] {miniBatchSize, inputSize, timeSeriesLength});
        INDArray labels = Nd4j.zeros(miniBatchSize, nClasses, timeSeriesLength);
        Random r = new Random(12345);
        for (int i = 0; i < miniBatchSize; i++) {
            for (int j = 0; j < timeSeriesLength; j++) {
                int idx = r.nextInt(nClasses);
                labels.putScalar(new int[] {i, idx, j}, 1.0);
            }
        }


        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().updater(Updater.NONE).seed(12345)
                        .weightInit(WeightInit.DISTRIBUTION).dist(new UniformDistribution(-2, 2)).list()
                        .layer(0, new ConvolutionLayer.Builder(5, 5).nIn(3).nOut(5).stride(1, 1)
                                        .activation(Activation.TANH).build()) //Out: (10-5)/1+1 = 6 -> 6x6x5
                        .layer(1, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX).kernelSize(2, 2)
                                        .stride(1, 1).build()) //Out: (6-2)/1+1 = 5 -> 5x5x5
                        .layer(2, new DenseLayer.Builder().nIn(5 * 5 * 5).nOut(4).activation(Activation.TANH).build())
                        .layer(3, new GravesLSTM.Builder().nIn(4).nOut(3).activation(Activation.TANH).build())
                        .layer(4, new RnnOutputLayer.Builder().lossFunction(LossFunction.MCXENT).nIn(3).nOut(nClasses)
                                        .activation(Activation.SOFTMAX).build())
                        .setInputType(InputType.convolutional(10, 10, 3)).pretrain(false).backprop(true).build();

        //Here: ConvolutionLayerSetup in config builder doesn't know that we are expecting time series input, not standard FF input -> override it here
        conf.getInputPreProcessors().put(0, new RnnToCnnPreProcessor(10, 10, 3));

        MultiLayerNetwork mln = new MultiLayerNetwork(conf);
        mln.init();

        System.out.println("Params per layer:");
        for (int i = 0; i < mln.getnLayers(); i++) {
            System.out.println("layer " + i + "\t" + mln.getLayer(i).numParams());
        }

        boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                        DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);
        assertTrue(gradOK);
    }


    @Test
    public void testRbm() {
        //As above (testGradientMLP2LayerIrisSimple()) but with L2, L1, and both L2/L1 applied
        //Need to run gradient through updater, so that L2 can be applied

        RBM.HiddenUnit[] hiddenFunc = {RBM.HiddenUnit.BINARY, RBM.HiddenUnit.RECTIFIED};
        boolean[] characteristic = {false, true}; //If true: run some backprop steps first

        LossFunction[] lossFunctions = {LossFunction.MSE, LossFunction.KL_DIVERGENCE};
        String[] outputActivations = {"softmax", "sigmoid"}; //i.e., lossFunctions[i] used with outputActivations[i] here

        DataNormalization scaler = new NormalizerMinMaxScaler();
        DataSetIterator iter = new IrisDataSetIterator(150, 150);
        scaler.fit(iter);
        iter.setPreProcessor(scaler);
        DataSet ds = iter.next();

        INDArray input = ds.getFeatureMatrix();
        INDArray labels = ds.getLabels();

        double[] l2vals = {0.4, 0.0, 0.4};
        double[] l1vals = {0.0, 0.5, 0.5}; //i.e., use l2vals[i] with l1vals[i]

        for (RBM.HiddenUnit hidunit : hiddenFunc) {
            for (boolean doLearningFirst : characteristic) {
                for (int i = 0; i < lossFunctions.length; i++) {
                    for (int k = 0; k < l2vals.length; k++) {
                        LossFunction lf = lossFunctions[i];
                        String outputActivation = outputActivations[i];
                        double l2 = l2vals[k];
                        double l1 = l1vals[k];

                        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().regularization(true).l2(l2)
                                        .l1(l1).learningRate(1.0)
                                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                                        .seed(12345L)
                                        .list().layer(0,
                                                        new RBM.Builder(hidunit, RBM.VisibleUnit.BINARY).nIn(4).nOut(3)
                                                                        .weightInit(WeightInit.UNIFORM).updater(
                                                                                        Updater.SGD)
                                                                        .build())
                                        .layer(1, new OutputLayer.Builder(lf).nIn(3).nOut(3)
                                                        .weightInit(WeightInit.XAVIER).updater(Updater.SGD)
                                                        .activation(outputActivation).build())
                                        .pretrain(false).backprop(true).build();

                        MultiLayerNetwork mln = new MultiLayerNetwork(conf);
                        mln.init();

                        if (doLearningFirst) {
                            //Run a number of iterations of learning
                            mln.setInput(ds.getFeatures());
                            mln.setLabels(ds.getLabels());
                            mln.computeGradientAndScore();
                            double scoreBefore = mln.score();
                            for (int j = 0; j < 10; j++)
                                mln.fit(ds);
                            mln.computeGradientAndScore();
                            double scoreAfter = mln.score();
                            //Can't test in 'characteristic mode of operation' if not learning
                            String msg = "testGradMLP2LayerIrisSimple() - score did not (sufficiently) decrease during learning - activationFn="
                                            + hidunit.toString() + ", lossFn=" + lf + ", outputActivation="
                                            + outputActivation + ", doLearningFirst=" + doLearningFirst + ", l2=" + l2
                                            + ", l1=" + l1 + " (before=" + scoreBefore + ", scoreAfter=" + scoreAfter
                                            + ")";
                            assertTrue(msg, scoreAfter < scoreBefore);
                        }

                        if (PRINT_RESULTS) {
                            System.out.println("testGradientMLP2LayerIrisSimpleRandom() - activationFn="
                                            + hidunit.toString() + ", lossFn=" + lf + ", outputActivation="
                                            + outputActivation + ", doLearningFirst=" + doLearningFirst + ", l2=" + l2
                                            + ", l1=" + l1);
                            for (int j = 0; j < mln.getnLayers(); j++)
                                System.out.println("Layer " + j + " # params: " + mln.getLayer(j).numParams());
                        }

                        boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                        DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                        String msg = "testGradMLP2LayerIrisSimple() - activationFn=" + hidunit.toString() + ", lossFn="
                                        + lf + ", outputActivation=" + outputActivation + ", doLearningFirst="
                                        + doLearningFirst + ", l2=" + l2 + ", l1=" + l1;
                        assertTrue(msg, gradOK);
                    }
                }
            }
        }
    }

    @Test
    public void testAutoEncoder() {
        //As above (testGradientMLP2LayerIrisSimple()) but with L2, L1, and both L2/L1 applied
        //Need to run gradient through updater, so that L2 can be applied

        String[] activFns = {"sigmoid", "tanh"};
        boolean[] characteristic = {false, true}; //If true: run some backprop steps first

        LossFunction[] lossFunctions = {LossFunction.MCXENT, LossFunction.MSE};
        String[] outputActivations = {"softmax", "tanh"}; //i.e., lossFunctions[i] used with outputActivations[i] here

        DataNormalization scaler = new NormalizerMinMaxScaler();
        DataSetIterator iter = new IrisDataSetIterator(150, 150);
        scaler.fit(iter);
        iter.setPreProcessor(scaler);
        DataSet ds = iter.next();
        INDArray input = ds.getFeatureMatrix();
        INDArray labels = ds.getLabels();

        NormalizerStandardize norm = new NormalizerStandardize();
        norm.fit(ds);
        norm.transform(ds);

        double[] l2vals = {0.2, 0.0, 0.2};
        double[] l1vals = {0.0, 0.3, 0.3}; //i.e., use l2vals[i] with l1vals[i]

        for (String afn : activFns) {
            for (boolean doLearningFirst : characteristic) {
                for (int i = 0; i < lossFunctions.length; i++) {
                    for (int k = 0; k < l2vals.length; k++) {
                        LossFunction lf = lossFunctions[i];
                        String outputActivation = outputActivations[i];
                        double l2 = l2vals[k];
                        double l1 = l1vals[k];

                        Nd4j.getRandom().setSeed(12345);
                        MultiLayerConfiguration conf =
                                        new NeuralNetConfiguration.Builder().regularization(true).learningRate(1.0)
                                                        .l2(l2).l1(l1)
                                                        .optimizationAlgo(OptimizationAlgorithm.CONJUGATE_GRADIENT)
                                                        .seed(12345L).weightInit(WeightInit.DISTRIBUTION)
                                                        .dist(new NormalDistribution(0, 1)).updater(Updater.SGD)
                                                        .list().layer(0,
                                                                        new AutoEncoder.Builder().nIn(4).nOut(3)
                                                                                        .activation(afn).build())
                                                        .layer(1, new OutputLayer.Builder(lf).nIn(3).nOut(3)
                                                                        .activation(outputActivation).build())
                                                        .pretrain(true).backprop(true).build();

                        MultiLayerNetwork mln = new MultiLayerNetwork(conf);
                        mln.init();

                        if (doLearningFirst) {
                            //Run a number of iterations of learning
                            mln.setInput(ds.getFeatures());
                            mln.setLabels(ds.getLabels());
                            mln.computeGradientAndScore();
                            double scoreBefore = mln.score();
                            for (int j = 0; j < 10; j++)
                                mln.fit(ds);
                            mln.computeGradientAndScore();
                            double scoreAfter = mln.score();
                            //Can't test in 'characteristic mode of operation' if not learning
                            String msg = "testGradMLP2LayerIrisSimple() - score did not (sufficiently) decrease during learning - activationFn="
                                            + afn + ", lossFn=" + lf + ", outputActivation=" + outputActivation
                                            + ", doLearningFirst=" + doLearningFirst + ", l2=" + l2 + ", l1=" + l1
                                            + " (before=" + scoreBefore + ", scoreAfter=" + scoreAfter + ")";
                            assertTrue(msg, scoreAfter < scoreBefore);
                        }

                        if (PRINT_RESULTS) {
                            System.out.println("testGradientMLP2LayerIrisSimpleRandom() - activationFn=" + afn
                                            + ", lossFn=" + lf + ", outputActivation=" + outputActivation
                                            + ", doLearningFirst=" + doLearningFirst + ", l2=" + l2 + ", l1=" + l1);
                            for (int j = 0; j < mln.getnLayers(); j++)
                                System.out.println("Layer " + j + " # params: " + mln.getLayer(j).numParams());
                        }

                        boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                        DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                        String msg = "testGradMLP2LayerIrisSimple() - activationFn=" + afn + ", lossFn=" + lf
                                        + ", outputActivation=" + outputActivation + ", doLearningFirst="
                                        + doLearningFirst + ", l2=" + l2 + ", l1=" + l1;
                        assertTrue(msg, gradOK);
                    }
                }
            }
        }
    }
}
