package org.deeplearning4j.parallelism.parameterserver;

import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.datasets.iterator.impl.MnistDataSetIterator;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.ConvolutionLayer;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.conf.layers.SubsamplingLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.junit.Test;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.lossfunctions.LossFunctions;

/**
 * Created by agibsonccc on 12/17/16.
 */
@Slf4j
public class ParameterServerParallelWrapperTest {

    @Test
    public void testWrapper() throws Exception {
        int nChannels = 1;
        int outputNum = 10;

        // for GPU you usually want to have higher batchSize
        int batchSize = 128;
        int nEpochs = 10;
        int iterations = 1;
        int seed = 123;

        log.info("Load data....");
        DataSetIterator mnistTrain = new MnistDataSetIterator(batchSize, 1000);
        DataSetIterator mnistTest = new MnistDataSetIterator(batchSize, false, 12345);

        log.info("Build model....");
        MultiLayerConfiguration.Builder builder = new NeuralNetConfiguration.Builder().seed(seed).iterations(iterations)
                        .regularization(true).l2(0.0005).learningRate(0.01)//.biasLearningRate(0.02)
                        //.learningRateDecayPolicy(LearningRatePolicy.Inverse).lrPolicyDecayRate(0.001).lrPolicyPower(0.75)
                        .weightInit(WeightInit.XAVIER)
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).updater(Updater.NESTEROVS)
                        .momentum(0.9).list()
                        .layer(0, new ConvolutionLayer.Builder(5, 5)
                                        //nIn and nOut specify depth. nIn here is the nChannels and nOut is the number of filters to be applied
                                        .nIn(nChannels).stride(1, 1).nOut(20).activation(Activation.IDENTITY).build())
                        .layer(1, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX).kernelSize(2, 2)
                                        .stride(2, 2).build())
                        .layer(2, new ConvolutionLayer.Builder(5, 5)
                                        //Note that nIn needed be specified in later layers
                                        .stride(1, 1).nOut(50).activation(Activation.IDENTITY).build())
                        .layer(3, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX).kernelSize(2, 2)
                                        .stride(2, 2).build())
                        .layer(4, new DenseLayer.Builder().activation(Activation.RELU).nOut(500).build())
                        .layer(5, new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                                        .nOut(outputNum).activation(Activation.SOFTMAX).build())
                        .setInputType(InputType.convolutionalFlat(28, 28, 1)).backprop(true).pretrain(false);

        MultiLayerConfiguration conf = builder.build();
        MultiLayerNetwork model = new MultiLayerNetwork(conf);
        model.init();

        ParameterServerParallelWrapper parameterServerParallelWrapper =
                        ParameterServerParallelWrapper.builder().model(model).multiLayerNetwork(model).numEpochs(10)
                                        .numWorkers(Runtime.getRuntime().availableProcessors()).statusServerPort(33000)
                                        .preFetchSize(3).build();
        parameterServerParallelWrapper.fit(mnistTrain);
        parameterServerParallelWrapper.close();

        Thread.sleep(30000);



    }

}
