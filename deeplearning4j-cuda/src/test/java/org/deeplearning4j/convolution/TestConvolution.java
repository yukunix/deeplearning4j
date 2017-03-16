package org.deeplearning4j.convolution;

import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.conf.ConvolutionMode;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.ConvolutionLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.conf.layers.SubsamplingLayer;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.junit.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by Alex on 15/11/2016.
 */
public class TestConvolution {

    @Test
    public void testSameModeActivationSizes(){
        int inH = 3;
        int inW = 4;
        int inDepth = 3;
        int minibatch = 5;

        int sH = 2;
        int sW = 2;
        int kH = 3;
        int kW = 3;

        org.deeplearning4j.nn.conf.layers.Layer[] l = new org.deeplearning4j.nn.conf.layers.Layer[2];
        l[0] = new ConvolutionLayer.Builder().nOut(4).kernelSize(kH,kW).stride(sH,sW).build();
        l[1] = new SubsamplingLayer.Builder().kernelSize(kH,kW).stride(sH,sW).build();

        for( int i=0; i<l.length; i++ ) {

            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                    .convolutionMode(ConvolutionMode.Same)
                    .list()
                    .layer(0, l[i])
                    .layer(1, new OutputLayer.Builder().nOut(3).build())
                    .setInputType(InputType.convolutional(inH, inW, inDepth))
                    .build();

            MultiLayerNetwork net = new MultiLayerNetwork(conf);
            net.init();

            INDArray inData = Nd4j.create(minibatch, inDepth, inH, inW);
            List<INDArray> activations = net.feedForward(inData);
            INDArray actL0 = activations.get(1);

            int outH = (int) Math.ceil(inH / ((double) sH));
            int outW = (int) Math.ceil(inW / ((double) sW));

            System.out.println(Arrays.toString(actL0.shape()));
            assertArrayEquals(new int[]{minibatch, (i == 0 ? 4 : inDepth), outH, outW}, actL0.shape());
        }
    }


    @Test
    public void testCompareCudnnStandardOutputsVsMode() throws Exception {

        ConvolutionMode[] cm = new ConvolutionMode[]{ConvolutionMode.Strict, ConvolutionMode.Same};

        for(ConvolutionMode c : cm ) {
            for( boolean conv : new boolean[]{true,false}) {

                org.deeplearning4j.nn.conf.layers.Layer l;
                if(conv){
                    l = new ConvolutionLayer.Builder().nOut(4).kernelSize(4,4).stride(2,2).build();
                } else {
                    l = new SubsamplingLayer.Builder().kernelSize(4,4).stride(2,2).build();
                }

                MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                        .seed(12345)
                        .regularization(true).l2(0.0005)
                        .learningRate(.01)
                        .weightInit(WeightInit.XAVIER)
                        .convolutionMode(c)
                        .list()
                        .layer(0, l)
                        .layer(1, new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                                .nOut(10)
                                .activation(Activation.SOFTMAX)
                                .build())
                        .setInputType(InputType.convolutionalFlat(28, 28, 1)) //See note below
                        .backprop(true).pretrain(false).build();

                Nd4j.getRandom().setSeed(12345);
                MultiLayerNetwork net1 = new MultiLayerNetwork(conf);
                net1.init();
                net1.initGradientsView();

                Nd4j.getRandom().setSeed(12345);
                MultiLayerNetwork net2 = new MultiLayerNetwork(conf);
                net2.init();
                net2.initGradientsView();

                Layer layerCudnn = net1.getLayer(0);
                Layer layerStandard = net2.getLayer(0);

                Field f = layerStandard.getClass().getDeclaredField("helper");
                f.setAccessible(true);
                f.set(layerStandard, null);

                if (f.get(layerCudnn) == null) throw new RuntimeException();
                if (f.get(layerStandard) != null ) throw new RuntimeException();


                INDArray in = Nd4j.rand(new int[]{1, 1, 20, 20});       //(20-4+0)/2 +1 = 9

                INDArray outCudnn = layerCudnn.activate(in);
                INDArray outStd = layerStandard.activate(in);

                assertEquals(outStd, outCudnn);


                //Check backprop:
                INDArray epsilon = Nd4j.rand(outStd.shape());
                Pair<Gradient,INDArray> pCudnn = layerCudnn.backpropGradient(epsilon);
                Pair<Gradient,INDArray> pStd = layerStandard.backpropGradient(epsilon);

                System.out.println(Arrays.toString(pStd.getSecond().data().asFloat()));
                System.out.println(Arrays.toString(pCudnn.getSecond().data().asFloat()));

                INDArray epsOutStd = pStd.getSecond();
                INDArray epsOutCudnn = pCudnn.getSecond();

                assertTrue(epsOutStd.equalsWithEps(epsOutCudnn, 1e-4));

                INDArray gradStd = pStd.getFirst().gradient();
                INDArray gradCudnn = pCudnn.getFirst().gradient();

                assertTrue(gradStd.equalsWithEps(gradCudnn, 1e-4));
            }
        }
    }
}
