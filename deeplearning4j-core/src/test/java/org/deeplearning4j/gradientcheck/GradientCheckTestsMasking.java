package org.deeplearning4j.gradientcheck;

import org.deeplearning4j.nn.conf.ComputationGraphConfiguration;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.distribution.NormalDistribution;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.junit.Test;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.buffer.util.DataTypeUtil;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.ILossFunction;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.nd4j.linalg.lossfunctions.impl.*;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertTrue;

/**Gradient checking tests with masking (i.e., variable length time series inputs, one-to-many and many-to-one etc)
 */
public class GradientCheckTestsMasking {

    private static final boolean PRINT_RESULTS = true;
    private static final boolean RETURN_ON_FIRST_FAILURE = false;
    private static final double DEFAULT_EPS = 1e-6;
    private static final double DEFAULT_MAX_REL_ERROR = 1e-3;
    private static final double DEFAULT_MIN_ABS_ERROR = 1e-10;

    static {
        //Force Nd4j initialization, then set data type to double:
        Nd4j.zeros(1);
        DataTypeUtil.setDTypeForContext(DataBuffer.Type.DOUBLE);
    }

    @Test
    public void gradientCheckMaskingOutputSimple() {

        int timeSeriesLength = 5;
        boolean[][] mask = new boolean[5][0];
        mask[0] = new boolean[] {true, true, true, true, true}; //No masking
        mask[1] = new boolean[] {false, true, true, true, true}; //mask first output time step
        mask[2] = new boolean[] {false, false, false, false, true}; //time series classification: mask all but last
        mask[3] = new boolean[] {false, false, true, false, true}; //time series classification w/ variable length TS
        mask[4] = new boolean[] {true, true, true, false, true}; //variable length TS

        int nIn = 4;
        int layerSize = 3;
        int nOut = 2;


        Random r = new Random(12345L);
        INDArray input = Nd4j.zeros(1, nIn, timeSeriesLength);
        for (int m = 0; m < 1; m++) {
            for (int j = 0; j < nIn; j++) {
                for (int k = 0; k < timeSeriesLength; k++) {
                    input.putScalar(new int[] {m, j, k}, r.nextDouble() - 0.5);
                }
            }
        }

        INDArray labels = Nd4j.zeros(1, nOut, timeSeriesLength);
        for (int m = 0; m < 1; m++) {
            for (int j = 0; j < timeSeriesLength; j++) {
                int idx = r.nextInt(nOut);
                labels.putScalar(new int[] {m, idx, j}, 1.0f);
            }
        }

        for (int i = 0; i < mask.length; i++) {

            //Create mask array:
            INDArray maskArr = Nd4j.create(1, timeSeriesLength);
            for (int j = 0; j < mask[i].length; j++) {
                maskArr.putScalar(new int[] {0, j}, mask[i][j] ? 1.0 : 0.0);
            }

            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().regularization(false).seed(12345L)
                            .list()
                            .layer(0, new GravesLSTM.Builder().nIn(nIn).nOut(layerSize)
                                            .weightInit(WeightInit.DISTRIBUTION).dist(new NormalDistribution(0, 1))
                                            .updater(Updater.NONE).build())
                            .layer(1, new RnnOutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                            .activation(Activation.SOFTMAX).nIn(layerSize).nOut(nOut)
                                            .weightInit(WeightInit.DISTRIBUTION).dist(new NormalDistribution(0, 1))
                                            .updater(Updater.NONE).build())
                            .pretrain(false).backprop(true).build();
            MultiLayerNetwork mln = new MultiLayerNetwork(conf);
            mln.init();

            mln.setLayerMaskArrays(null, maskArr);

            boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                            DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

            String msg = "gradientCheckMaskingOutputSimple() - timeSeriesLength=" + timeSeriesLength
                            + ", miniBatchSize=" + 1;
            assertTrue(msg, gradOK);

        }
    }

    @Test
    public void testBidirectionalLSTMMasking() {
        //Basic test of GravesLSTM layer
        Nd4j.getRandom().setSeed(12345L);

        int timeSeriesLength = 5;
        int nIn = 5;
        int layerSize = 4;
        int nOut = 3;

        int miniBatchSize = 3;

        INDArray[] masks = new INDArray[] {null,
                        Nd4j.create(new double[][] {{1, 1, 1, 1, 1}, {1, 1, 1, 1, 1}, {1, 1, 1, 1, 1}}),
                        Nd4j.create(new double[][] {{1, 1, 1, 1, 1}, {1, 1, 1, 1, 0}, {1, 1, 1, 0, 0}}),
                        Nd4j.create(new double[][] {{1, 1, 1, 1, 1}, {0, 1, 1, 1, 1}, {0, 0, 1, 1, 1}})};

        int testNum = 0;
        for (INDArray mask : masks) {

            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().regularization(false)
                            .updater(Updater.NONE).weightInit(WeightInit.DISTRIBUTION)
                            .dist(new NormalDistribution(0, 1.0)).seed(12345L).list()
                            .layer(0, new GravesBidirectionalLSTM.Builder().nIn(nIn).nOut(layerSize)
                                            .activation(Activation.TANH).build())
                            .layer(1, new GravesBidirectionalLSTM.Builder().nIn(layerSize).nOut(layerSize)
                                            .activation(Activation.TANH).build())
                            .layer(2, new RnnOutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                            .activation(Activation.SOFTMAX).nIn(layerSize).nOut(nOut).build())
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
                for (int j = 0; j < nIn; j++) {
                    labels.putScalar(i, r.nextInt(nOut), j, 1.0);
                }
            }

            mln.setLayerMaskArrays(mask, mask);

            if (PRINT_RESULTS) {
                System.out.println("testBidirectionalLSTMMasking() - testNum = " + testNum++);
                for (int j = 0; j < mln.getnLayers(); j++)
                    System.out.println("Layer " + j + " # params: " + mln.getLayer(j).numParams());
            }

            boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                            DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

            assertTrue(gradOK);
        }
    }


    @Test
    public void testPerOutputMaskingMLP() {
        int nIn = 6;
        int layerSize = 4;

        INDArray mask1 = Nd4j.create(new double[] {1, 0, 0, 1, 0});
        INDArray mask3 = Nd4j.create(new double[][] {{1, 1, 1, 1, 1}, {0, 1, 0, 1, 0}, {1, 0, 0, 1, 1}});
        INDArray[] labelMasks = new INDArray[] {mask1, mask3};

        ILossFunction[] lossFunctions = new ILossFunction[] {new LossBinaryXENT(),
                        //                new LossCosineProximity(),    //Doesn't support per-output masking, as it doesn't make sense for cosine proximity
                        new LossHinge(), new LossKLD(), new LossKLD(), new LossL1(), new LossL2(), new LossMAE(),
                        new LossMAE(), new LossMAPE(), new LossMAPE(),
                        //                new LossMCXENT(),             //Per output masking on MCXENT+Softmax: not yet supported
                        new LossMCXENT(), new LossMSE(), new LossMSE(), new LossMSLE(), new LossMSLE(),
                        new LossNegativeLogLikelihood(), new LossPoisson(), new LossSquaredHinge()};

        Activation[] act = new Activation[] {Activation.SIGMOID, //XENT
                        //                Activation.TANH,
                        Activation.TANH, //Hinge
                        Activation.SIGMOID, //KLD
                        Activation.SOFTMAX, //KLD + softmax
                        Activation.TANH, //L1
                        Activation.TANH, //L2
                        Activation.TANH, //MAE
                        Activation.SOFTMAX, //MAE + softmax
                        Activation.TANH, //MAPE
                        Activation.SOFTMAX, //MAPE + softmax
                        //                Activation.SOFTMAX, //MCXENT + softmax: see comment above
                        Activation.SIGMOID, //MCXENT + sigmoid
                        Activation.TANH, //MSE
                        Activation.SOFTMAX, //MSE + softmax
                        Activation.SIGMOID, //MSLE - needs positive labels/activations (due to log)
                        Activation.SOFTMAX, //MSLE + softmax
                        Activation.SIGMOID, //NLL
                        Activation.SIGMOID, //Poisson
                        Activation.TANH //Squared hinge
        };

        for (INDArray labelMask : labelMasks) {

            int minibatch = labelMask.size(0);
            int nOut = labelMask.size(1);

            for (int i = 0; i < lossFunctions.length; i++) {
                ILossFunction lf = lossFunctions[i];
                Activation a = act[i];


                MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().updater(Updater.NONE)
                                .weightInit(WeightInit.DISTRIBUTION).dist(new NormalDistribution(0, 1)).seed(12345)
                                .list()
                                .layer(0, new DenseLayer.Builder().nIn(nIn).nOut(layerSize).activation(Activation.TANH)
                                                .build())
                                .layer(1, new OutputLayer.Builder().nIn(layerSize).nOut(nOut).lossFunction(lf)
                                                .activation(a).build())
                                .build();

                MultiLayerNetwork net = new MultiLayerNetwork(conf);
                net.init();

                net.setLayerMaskArrays(null, labelMask);
                INDArray[] fl = LossFunctionGradientCheck.getFeaturesAndLabels(lf, minibatch, nIn, nOut, 12345);
                INDArray features = fl[0];
                INDArray labels = fl[1];

                String msg = "testPerOutputMaskingMLP(): maskShape = " + Arrays.toString(labelMask.shape())
                                + ", loss function = " + lf + ", activation = " + a;

                System.out.println(msg);

                boolean gradOK = GradientCheckUtil.checkGradients(net, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, features, labels);

                assertTrue(msg, gradOK);
            }
        }
    }

    @Test
    public void testPerOutputMaskingRnn() {
        //For RNNs: per-output masking uses 3d masks (same shape as output/labels), as compared to the standard
        // 2d masks (used for per *example* masking)

        int nIn = 4;
        int layerSize = 4;
        int nOut = 4;

        //1 example, TS length 3
        INDArray mask1 = Nd4j.create(new double[] {1, 0, 0, 1, 0, 1, 0, 1, 1, 1, 1, 0}, new int[] {1, nOut, 3}, 'f');
        //1 example, TS length 1
        INDArray mask2 = Nd4j.create(new double[] {1, 1, 0, 1}, new int[] {1, nOut, 1}, 'f');
        //3 examples, TS length 3
        INDArray mask3 = Nd4j.create(new double[] {
                        //With fortran order: dimension 0 (example) changes quickest, followed by dimension 1 (value within time
                        // step) followed by time index (least frequently)
                        1, 0, 1, 0, 1, 1, 1, 1, 1, 0, 1, 0,

                        0, 1, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1,

                        1, 1, 1, 0, 0, 1, 0, 1, 0, 1, 0, 0}, new int[] {3, nOut, 3}, 'f');
        INDArray[] labelMasks = new INDArray[] {mask1, mask2, mask3};

        ILossFunction[] lossFunctions = new ILossFunction[] {new LossBinaryXENT(),
                        //                new LossCosineProximity(),    //Doesn't support per-output masking, as it doesn't make sense for cosine proximity
                        new LossHinge(), new LossKLD(), new LossKLD(), new LossL1(), new LossL2(), new LossMAE(),
                        new LossMAE(), new LossMAPE(), new LossMAPE(),
                        //                new LossMCXENT(),             //Per output masking on MCXENT+Softmax: not yet supported
                        new LossMCXENT(), new LossMSE(), new LossMSE(), new LossMSLE(), new LossMSLE(),
                        new LossNegativeLogLikelihood(), new LossPoisson(), new LossSquaredHinge()};

        Activation[] act = new Activation[] {Activation.SIGMOID, //XENT
                        //                Activation.TANH,
                        Activation.TANH, //Hinge
                        Activation.SIGMOID, //KLD
                        Activation.SOFTMAX, //KLD + softmax
                        Activation.TANH, //L1
                        Activation.TANH, //L2
                        Activation.TANH, //MAE
                        Activation.SOFTMAX, //MAE + softmax
                        Activation.TANH, //MAPE
                        Activation.SOFTMAX, //MAPE + softmax
                        //                Activation.SOFTMAX, //MCXENT + softmax: see comment above
                        Activation.SIGMOID, //MCXENT + sigmoid
                        Activation.TANH, //MSE
                        Activation.SOFTMAX, //MSE + softmax
                        Activation.SIGMOID, //MSLE - needs positive labels/activations (due to log)
                        Activation.SOFTMAX, //MSLE + softmax
                        Activation.SIGMOID, //NLL
                        Activation.SIGMOID, //Poisson
                        Activation.TANH //Squared hinge
        };

        for (INDArray labelMask : labelMasks) {

            int minibatch = labelMask.size(0);
            int tsLength = labelMask.size(2);

            for (int i = 0; i < lossFunctions.length; i++) {
                ILossFunction lf = lossFunctions[i];
                Activation a = act[i];

                Nd4j.getRandom().setSeed(12345);
                MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().updater(Updater.NONE)
                                .weightInit(WeightInit.DISTRIBUTION).dist(new NormalDistribution(0, 1)).seed(12345)
                                .list()
                                .layer(0, new GravesLSTM.Builder().nIn(nIn).nOut(layerSize).activation(Activation.TANH)
                                                .build())
                                .layer(1, new RnnOutputLayer.Builder().nIn(layerSize).nOut(nOut).lossFunction(lf)
                                                .activation(a).build())
                                .build();

                MultiLayerNetwork net = new MultiLayerNetwork(conf);
                net.init();

                net.setLayerMaskArrays(null, labelMask);
                INDArray[] fl = LossFunctionGradientCheck.getFeaturesAndLabels(lf, new int[] {minibatch, nIn, tsLength},
                                new int[] {minibatch, nOut, tsLength}, 12345);
                INDArray features = fl[0];
                INDArray labels = fl[1];

                String msg = "testPerOutputMaskingRnn(): maskShape = " + Arrays.toString(labelMask.shape())
                                + ", loss function = " + lf + ", activation = " + a;

                System.out.println(msg);

                boolean gradOK = GradientCheckUtil.checkGradients(net, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, features, labels);

                assertTrue(msg, gradOK);


                //Check the equivalent compgraph:

                Nd4j.getRandom().setSeed(12345);
                ComputationGraphConfiguration cg = new NeuralNetConfiguration.Builder().updater(Updater.NONE)
                                .weightInit(WeightInit.DISTRIBUTION).dist(new NormalDistribution(0, 2)).seed(12345)
                                .graphBuilder().addInputs("in")
                                .addLayer("0", new GravesLSTM.Builder().nIn(nIn).nOut(layerSize)
                                                .activation(Activation.TANH).build(), "in")
                                .addLayer("1", new RnnOutputLayer.Builder().nIn(layerSize).nOut(nOut).lossFunction(lf)
                                                .activation(a).build(), "0")
                                .setOutputs("1").build();

                ComputationGraph graph = new ComputationGraph(cg);
                graph.init();

                net.setLayerMaskArrays(null, labelMask);

                gradOK = GradientCheckUtil.checkGradients(graph, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE,
                                new INDArray[] {features}, new INDArray[] {labels});

                assertTrue(msg + " (compgraph)", gradOK);
            }
        }
    }
}
