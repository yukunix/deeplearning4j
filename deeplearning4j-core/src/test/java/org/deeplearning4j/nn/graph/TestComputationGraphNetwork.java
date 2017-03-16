package org.deeplearning4j.nn.graph;

import org.datavec.api.records.reader.RecordReader;
import org.datavec.api.records.reader.impl.csv.CSVRecordReader;
import org.datavec.api.split.FileSplit;
import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.datasets.datavec.RecordReaderMultiDataSetIterator;
import org.deeplearning4j.datasets.iterator.impl.IrisDataSetIterator;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.*;
import org.deeplearning4j.nn.conf.distribution.UniformDistribution;
import org.deeplearning4j.nn.conf.graph.LayerVertex;
import org.deeplearning4j.nn.conf.graph.MergeVertex;
import org.deeplearning4j.nn.conf.graph.SubsetVertex;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.conf.preprocessor.CnnToFeedForwardPreProcessor;
import org.deeplearning4j.nn.conf.preprocessor.FeedForwardToCnnPreProcessor;
import org.deeplearning4j.nn.conf.preprocessor.FeedForwardToRnnPreProcessor;
import org.deeplearning4j.nn.conf.preprocessor.RnnToFeedForwardPreProcessor;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.transferlearning.TransferLearning;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.util.ModelSerializer;
import org.junit.Test;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.dataset.api.iterator.MultiDataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.io.ClassPathResource;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class TestComputationGraphNetwork {

    private static ComputationGraphConfiguration getIrisGraphConfiguration() {
        return new NeuralNetConfiguration.Builder().seed(12345)
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).graphBuilder()
                        .addInputs("input")
                        .addLayer("firstLayer", new DenseLayer.Builder().nIn(4).nOut(5).build(), "input")
                        .addLayer("outputLayer", new OutputLayer.Builder().nIn(5).nOut(3).build(), "firstLayer")
                        .setOutputs("outputLayer").build();
    }

    private static MultiLayerConfiguration getIrisMLNConfiguration() {
        return new NeuralNetConfiguration.Builder().seed(12345)
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).list()
                        .layer(0, new DenseLayer.Builder().nIn(4).nOut(5).build())
                        .layer(1, new OutputLayer.Builder().nIn(5).nOut(3).build()).build();
    }

    public ComputationGraph getRBMModel(boolean preTrain, int nIn, int nOut) {
        ComputationGraphConfiguration rbm = new NeuralNetConfiguration.Builder().seed(42).iterations(1).graphBuilder()
                        .addInputs("input")
                        .addLayer("firstLayer",
                                        new org.deeplearning4j.nn.conf.layers.RBM.Builder()
                                                        .lossFunction(LossFunctions.LossFunction.COSINE_PROXIMITY)
                                                        .activation(Activation.IDENTITY).nIn(nIn).nOut(nOut).build(),
                                        "input")
                        .addLayer("outputLayer",
                                        new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                                        LossFunctions.LossFunction.COSINE_PROXIMITY)
                                                                        .activation(Activation.IDENTITY).nIn(nOut)
                                                                        .nOut(nOut).build(),
                                        "firstLayer")
                        .setOutputs("outputLayer").pretrain(preTrain).build();
        ComputationGraph graph = new ComputationGraph(rbm);
        graph.init();
        return graph;
    }

    private static int getNumParams() {
        //Number of parameters for both iris models
        return (4 * 5 + 5) + (5 * 3 + 3);
    }

    @Test
    public void testConfigurationBasic() {

        ComputationGraphConfiguration configuration = getIrisGraphConfiguration();

        ComputationGraph graph = new ComputationGraph(configuration);
        graph.init();

        //Get topological sort order
        int[] order = graph.topologicalSortOrder();
        int[] expOrder = new int[] {0, 1, 2};
        assertArrayEquals(expOrder, order); //Only one valid order: 0 (input) -> 1 (firstlayer) -> 2 (outputlayer)

        INDArray params = graph.params();
        assertNotNull(params);

        int nParams = getNumParams();
        assertEquals(nParams, params.length());

        INDArray arr = Nd4j.linspace(0, nParams, nParams);
        assertEquals(nParams, arr.length());

        graph.setParams(arr);
        params = graph.params();
        assertEquals(arr, params);

        //Number of inputs and outputs:
        assertEquals(1, graph.getNumInputArrays());
        assertEquals(1, graph.getNumOutputArrays());
    }

    @Test
    public void testForwardBasicIris() {

        ComputationGraphConfiguration configuration = getIrisGraphConfiguration();
        ComputationGraph graph = new ComputationGraph(configuration);
        graph.init();

        MultiLayerConfiguration mlc = getIrisMLNConfiguration();
        MultiLayerNetwork net = new MultiLayerNetwork(mlc);
        net.init();

        DataSetIterator iris = new IrisDataSetIterator(150, 150);
        DataSet ds = iris.next();

        graph.setInput(0, ds.getFeatureMatrix());
        Map<String, INDArray> activations = graph.feedForward(false);
        assertEquals(3, activations.size()); //2 layers + 1 input node
        assertTrue(activations.containsKey("input"));
        assertTrue(activations.containsKey("firstLayer"));
        assertTrue(activations.containsKey("outputLayer"));

        //Now: set parameters of both networks to be identical. Then feedforward, and check we get the same outputs
        Nd4j.getRandom().setSeed(12345);
        int nParams = getNumParams();
        INDArray params = Nd4j.rand(1, nParams);
        graph.setParams(params.dup());
        net.setParams(params.dup());

        List<INDArray> mlnAct = net.feedForward(ds.getFeatureMatrix(), false);
        activations = graph.feedForward(ds.getFeatureMatrix(), false);

        assertEquals(mlnAct.get(0), activations.get("input"));
        assertEquals(mlnAct.get(1), activations.get("firstLayer"));
        assertEquals(mlnAct.get(2), activations.get("outputLayer"));
    }

    @Test
    public void testBackwardIrisBasic() {
        ComputationGraphConfiguration configuration = getIrisGraphConfiguration();
        ComputationGraph graph = new ComputationGraph(configuration);
        graph.init();

        MultiLayerConfiguration mlc = getIrisMLNConfiguration();
        MultiLayerNetwork net = new MultiLayerNetwork(mlc);
        net.init();

        DataSetIterator iris = new IrisDataSetIterator(150, 150);
        DataSet ds = iris.next();

        //Now: set parameters of both networks to be identical. Then feedforward, and check we get the same outputs
        Nd4j.getRandom().setSeed(12345);
        int nParams = (4 * 5 + 5) + (5 * 3 + 3);
        INDArray params = Nd4j.rand(1, nParams);
        graph.setParams(params.dup());
        net.setParams(params.dup());

        INDArray input = ds.getFeatureMatrix();
        INDArray labels = ds.getLabels();
        graph.setInput(0, input.dup());
        graph.setLabel(0, labels.dup());

        net.setInput(input.dup());
        net.setLabels(labels.dup());

        //Compute gradients
        net.computeGradientAndScore();
        Pair<Gradient, Double> netGradScore = net.gradientAndScore();

        graph.computeGradientAndScore();
        Pair<Gradient, Double> graphGradScore = graph.gradientAndScore();

        assertEquals(netGradScore.getSecond(), graphGradScore.getSecond(), 1e-3);

        //Compare gradients
        Gradient netGrad = netGradScore.getFirst();
        Gradient graphGrad = graphGradScore.getFirst();

        assertNotNull(graphGrad);
        assertEquals(netGrad.gradientForVariable().size(), graphGrad.gradientForVariable().size());

        assertEquals(netGrad.getGradientFor("0_W"), graphGrad.getGradientFor("firstLayer_W"));
        assertEquals(netGrad.getGradientFor("0_b"), graphGrad.getGradientFor("firstLayer_b"));
        assertEquals(netGrad.getGradientFor("1_W"), graphGrad.getGradientFor("outputLayer_W"));
        assertEquals(netGrad.getGradientFor("1_b"), graphGrad.getGradientFor("outputLayer_b"));
    }

    @Test
    public void testIrisFit() {

        ComputationGraphConfiguration configuration = getIrisGraphConfiguration();
        ComputationGraph graph = new ComputationGraph(configuration);
        graph.init();

        MultiLayerConfiguration mlnConfig = getIrisMLNConfiguration();
        MultiLayerNetwork net = new MultiLayerNetwork(mlnConfig);
        net.init();

        Nd4j.getRandom().setSeed(12345);
        int nParams = getNumParams();
        INDArray params = Nd4j.rand(1, nParams);

        graph.setParams(params.dup());
        net.setParams(params.dup());


        DataSetIterator iris = new IrisDataSetIterator(75, 150);

        net.fit(iris);
        iris.reset();

        graph.fit(iris);

        //Check that parameters are equal for both models after fitting:
        INDArray paramsMLN = net.params();
        INDArray paramsGraph = graph.params();

        assertNotEquals(params, paramsGraph);
        assertEquals(paramsMLN, paramsGraph);
    }

    @Test
    public void testIrisFitMultiDataSetIterator() throws Exception {

        RecordReader rr = new CSVRecordReader(0, ",");
        rr.initialize(new FileSplit(new ClassPathResource("iris.txt").getTempFileFromArchive()));

        MultiDataSetIterator iter = new RecordReaderMultiDataSetIterator.Builder(10).addReader("iris", rr)
                        .addInput("iris", 0, 3).addOutputOneHot("iris", 4, 3).build();

        ComputationGraphConfiguration config = new NeuralNetConfiguration.Builder()
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).learningRate(0.1)
                        .graphBuilder().addInputs("in")
                        .addLayer("dense", new DenseLayer.Builder().nIn(4).nOut(2).build(), "in").addLayer("out",
                                        new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT).nIn(2).nOut(3)
                                                        .build(),
                                        "dense")
                        .setOutputs("out").pretrain(false).backprop(true).build();

        ComputationGraph cg = new ComputationGraph(config);
        cg.init();

        cg.fit(iter);


        rr.reset();
        iter = new RecordReaderMultiDataSetIterator.Builder(10).addReader("iris", rr).addInput("iris", 0, 3)
                        .addOutputOneHot("iris", 4, 3).build();
        while (iter.hasNext()) {
            cg.fit(iter.next());
        }
    }

    @Test
    public void testCloning() {
        Nd4j.getRandom().setSeed(12345);
        ComputationGraphConfiguration conf = getIrisGraphConfiguration();
        ComputationGraph graph = new ComputationGraph(conf);
        graph.init();

        ComputationGraph g2 = graph.clone();

        DataSetIterator iris = new IrisDataSetIterator(150, 150);
        INDArray in = iris.next().getFeatureMatrix();
        Map<String, INDArray> activations = graph.feedForward(in, false);
        Map<String, INDArray> activations2 = g2.feedForward(in, false);
        assertEquals(activations, activations2);
    }

    @Test
    public void testScoringDataSet() {
        ComputationGraphConfiguration configuration = getIrisGraphConfiguration();
        ComputationGraph graph = new ComputationGraph(configuration);
        graph.init();

        MultiLayerConfiguration mlc = getIrisMLNConfiguration();
        MultiLayerNetwork net = new MultiLayerNetwork(mlc);
        net.init();

        DataSetIterator iris = new IrisDataSetIterator(150, 150);
        DataSet ds = iris.next();

        //Now: set parameters of both networks to be identical. Then feedforward, and check we get the same score
        Nd4j.getRandom().setSeed(12345);
        int nParams = getNumParams();
        INDArray params = Nd4j.rand(1, nParams);
        graph.setParams(params.dup());
        net.setParams(params.dup());

        double scoreMLN = net.score(ds, false);
        double scoreCG = graph.score(ds, false);

        assertEquals(scoreMLN, scoreCG, 1e-4);
    }

    @Test
    public void testPreprocessorAddition() {
        //Also check that nIns are set automatically
        //First: check FF -> RNN
        ComputationGraphConfiguration conf1 = new NeuralNetConfiguration.Builder().graphBuilder().addInputs("in")
                        .setInputTypes(InputType.feedForward(5))
                        .addLayer("rnn", new GravesLSTM.Builder().nOut(5).build(), "in")
                        .addLayer("out", new RnnOutputLayer.Builder().nOut(5).build(), "rnn").setOutputs("out").build();

        assertEquals(5, ((FeedForwardLayer) ((LayerVertex) conf1.getVertices().get("rnn")).getLayerConf().getLayer())
                        .getNIn());
        assertEquals(5, ((FeedForwardLayer) ((LayerVertex) conf1.getVertices().get("out")).getLayerConf().getLayer())
                        .getNIn());

        LayerVertex lv1 = (LayerVertex) conf1.getVertices().get("rnn");
        assertTrue(lv1.getPreProcessor() instanceof FeedForwardToRnnPreProcessor);
        LayerVertex lv2 = (LayerVertex) conf1.getVertices().get("out");
        assertNull(lv2.getPreProcessor());

        //Check RNN -> FF -> RNN
        ComputationGraphConfiguration conf2 = new NeuralNetConfiguration.Builder().graphBuilder().addInputs("in")
                        .setInputTypes(InputType.recurrent(5))
                        .addLayer("ff", new DenseLayer.Builder().nOut(5).build(), "in")
                        .addLayer("out", new RnnOutputLayer.Builder().nOut(5).build(), "ff").setOutputs("out").build();

        assertEquals(5, ((FeedForwardLayer) ((LayerVertex) conf2.getVertices().get("ff")).getLayerConf().getLayer())
                        .getNIn());
        assertEquals(5, ((FeedForwardLayer) ((LayerVertex) conf2.getVertices().get("out")).getLayerConf().getLayer())
                        .getNIn());

        lv1 = (LayerVertex) conf2.getVertices().get("ff");
        assertTrue(lv1.getPreProcessor() instanceof RnnToFeedForwardPreProcessor);
        lv2 = (LayerVertex) conf2.getVertices().get("out");
        assertTrue(lv2.getPreProcessor() instanceof FeedForwardToRnnPreProcessor);

        //CNN -> Dense
        ComputationGraphConfiguration conf3 = new NeuralNetConfiguration.Builder().graphBuilder().addInputs("in")
                        .setInputTypes(InputType.convolutional(28, 28, 1))
                        .addLayer("cnn", new ConvolutionLayer.Builder().kernelSize(2, 2).padding(0, 0).stride(2, 2)
                                        .nOut(3).build(), "in") //(28-2+0)/2+1 = 14
                        .addLayer("pool",
                                        new SubsamplingLayer.Builder().kernelSize(2, 2).padding(0, 0).stride(2, 2)
                                                        .build(),
                                        "cnn") //(14-2+0)/2+1=7
                        .addLayer("dense", new DenseLayer.Builder().nOut(10).build(), "pool")
                        .addLayer("out", new OutputLayer.Builder().nIn(10).nOut(5).build(), "dense").setOutputs("out")
                        .build();
        //Check preprocessors:
        lv1 = (LayerVertex) conf3.getVertices().get("cnn");
        assertNull(lv1.getPreProcessor()); //Shouldn't be adding preprocessor here

        lv2 = (LayerVertex) conf3.getVertices().get("pool");
        assertNull(lv2.getPreProcessor());
        LayerVertex lv3 = (LayerVertex) conf3.getVertices().get("dense");
        assertTrue(lv3.getPreProcessor() instanceof CnnToFeedForwardPreProcessor);
        CnnToFeedForwardPreProcessor proc = (CnnToFeedForwardPreProcessor) lv3.getPreProcessor();
        assertEquals(3, proc.getNumChannels());
        assertEquals(7, proc.getInputHeight());
        assertEquals(7, proc.getInputWidth());
        LayerVertex lv4 = (LayerVertex) conf3.getVertices().get("out");
        assertNull(lv4.getPreProcessor());
        //Check nIns:
        assertEquals(7 * 7 * 3, ((FeedForwardLayer) lv3.getLayerConf().getLayer()).getNIn());

        //CNN->Dense, RNN->Dense, Dense->RNN
        ComputationGraphConfiguration conf4 =
                        new NeuralNetConfiguration.Builder().graphBuilder().addInputs("inCNN", "inRNN")
                                        .setInputTypes(InputType.convolutional(28, 28, 1), InputType.recurrent(5))
                                        .addLayer("cnn", new ConvolutionLayer.Builder().kernelSize(2, 2).padding(0, 0)
                                                        .stride(2, 2).nOut(3).build(), "inCNN") //(28-2+0)/2+1 = 14
                                        .addLayer("pool",
                                                        new SubsamplingLayer.Builder().kernelSize(2, 2).padding(0, 0)
                                                                        .stride(2, 2).build(),
                                                        "cnn") //(14-2+0)/2+1=7
                                        .addLayer("dense", new DenseLayer.Builder().nOut(10).build(), "pool")
                                        .addLayer("dense2", new DenseLayer.Builder().nOut(10).build(), "inRNN")
                                        .addVertex("merge", new MergeVertex(), "dense", "dense2")
                                        .addLayer("out", new RnnOutputLayer.Builder().nOut(5).build(), "merge")
                                        .setOutputs("out").build();

        //Check preprocessors:
        lv1 = (LayerVertex) conf4.getVertices().get("cnn");
        assertNull(lv1.getPreProcessor()); //Expect no preprocessor: cnn data -> cnn layer

        lv2 = (LayerVertex) conf4.getVertices().get("pool");
        assertNull(lv2.getPreProcessor());
        lv3 = (LayerVertex) conf4.getVertices().get("dense");
        assertTrue(lv3.getPreProcessor() instanceof CnnToFeedForwardPreProcessor);
        proc = (CnnToFeedForwardPreProcessor) lv3.getPreProcessor();
        assertEquals(3, proc.getNumChannels());
        assertEquals(7, proc.getInputHeight());
        assertEquals(7, proc.getInputWidth());
        lv4 = (LayerVertex) conf4.getVertices().get("dense2");
        assertTrue(lv4.getPreProcessor() instanceof RnnToFeedForwardPreProcessor);
        LayerVertex lv5 = (LayerVertex) conf4.getVertices().get("out");
        assertTrue(lv5.getPreProcessor() instanceof FeedForwardToRnnPreProcessor);
        //Check nIns:
        assertEquals(7 * 7 * 3, ((FeedForwardLayer) lv3.getLayerConf().getLayer()).getNIn());
        assertEquals(5, ((FeedForwardLayer) lv4.getLayerConf().getLayer()).getNIn());
        assertEquals(20, ((FeedForwardLayer) lv5.getLayerConf().getLayer()).getNIn()); //10+10 out of the merge vertex -> 20 in to output layer vertex



        //Input to 2 CNN layers:
        ComputationGraphConfiguration conf5 =
                        new NeuralNetConfiguration.Builder()
                                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                                        .graphBuilder().addInputs("input")
                                        .setInputTypes(InputType.convolutional(28, 28, 1))
                                        .addLayer("cnn_1",
                                                        new ConvolutionLayer.Builder(2, 2).stride(2, 2).nIn(1).nOut(3)
                                                                        .build(),
                                                        "input")
                                        .addLayer("cnn_2",
                                                        new ConvolutionLayer.Builder(4, 4).stride(2, 2).padding(1, 1)
                                                                        .nIn(1).nOut(3).build(),
                                                        "input")
                                        .addLayer("max_1",
                                                        new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX)
                                                                        .kernelSize(2, 2).build(),
                                                        "cnn_1", "cnn_2")
                                        .addLayer("output", new OutputLayer.Builder().nOut(10).build(), "max_1") //.nIn(7 * 7 * 6)
                                        .setOutputs("output").pretrain(false).backprop(true).build();
        lv1 = (LayerVertex) conf5.getVertices().get("cnn_1");
        assertNull(lv1.getPreProcessor()); //Expect no preprocessor: cnn data -> cnn layer

        lv2 = (LayerVertex) conf5.getVertices().get("cnn_2");
        assertNull(lv2.getPreProcessor()); //Expect no preprocessor: cnn data -> cnn layer

        assertNull(((LayerVertex) conf5.getVertices().get("max_1")).getPreProcessor());

        lv3 = (LayerVertex) conf5.getVertices().get("output");
        assertTrue(lv3.getPreProcessor() instanceof CnnToFeedForwardPreProcessor);
        CnnToFeedForwardPreProcessor cnnff = (CnnToFeedForwardPreProcessor) lv3.getPreProcessor();
        assertEquals(6, cnnff.getNumChannels());
        assertEquals(7, cnnff.getInputHeight());
        assertEquals(7, cnnff.getInputWidth());
    }

    @Test
    public void testCompGraphUnderscores() {
        //Problem: underscores in names could be problematic for ComputationGraphUpdater, HistogramIterationListener

        ComputationGraphConfiguration conf = new NeuralNetConfiguration.Builder()
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).graphBuilder()
                        .addInputs("input")
                        .addLayer("first_layer", new DenseLayer.Builder().nIn(4).nOut(5).build(), "input")
                        .addLayer("output_layer", new OutputLayer.Builder().nIn(5).nOut(3).build(), "first_layer")
                        .setOutputs("output_layer").pretrain(false).backprop(true).build();

        ComputationGraph net = new ComputationGraph(conf);
        net.init();

        DataSetIterator iris = new IrisDataSetIterator(10, 150);
        while (iris.hasNext()) {
            net.fit(iris.next());
        }
    }

    @Test
    public void testPreTraining() {
        ComputationGraphConfiguration conf =
                        new NeuralNetConfiguration.Builder().iterations(100)
                                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                                        .iterations(1).updater(Updater.SGD).learningRate(1e-6).regularization(true)
                                        .l2(2e-4).graphBuilder().addInputs("in")
                                        .addLayer("layer0",
                                                        new RBM.Builder(RBM.HiddenUnit.GAUSSIAN,
                                                                        RBM.VisibleUnit.GAUSSIAN).nIn(4).nOut(3)
                                                                                        .weightInit(WeightInit.DISTRIBUTION)
                                                                                        .dist(new UniformDistribution(0,
                                                                                                        1))
                                                                                        .activation(Activation.TANH)
                                                                                        .lossFunction(LossFunctions.LossFunction.KL_DIVERGENCE)
                                                                                        .build(),
                                                        "in")
                                        .addLayer("layer1",
                                                        new RBM.Builder(RBM.HiddenUnit.GAUSSIAN,
                                                                        RBM.VisibleUnit.GAUSSIAN).nIn(4).nOut(3)
                                                                                        .weightInit(WeightInit.DISTRIBUTION)
                                                                                        .dist(new UniformDistribution(0,
                                                                                                        1))
                                                                                        .activation(Activation.TANH)
                                                                                        .lossFunction(LossFunctions.LossFunction.KL_DIVERGENCE)
                                                                                        .build(),
                                                        "in")
                                        .addLayer("layer2",
                                                        new RBM.Builder(RBM.HiddenUnit.GAUSSIAN,
                                                                        RBM.VisibleUnit.GAUSSIAN).nIn(3).nOut(3)
                                                                                        .weightInit(WeightInit.DISTRIBUTION)
                                                                                        .dist(new UniformDistribution(0,
                                                                                                        1))
                                                                                        .activation(Activation.TANH)
                                                                                        .lossFunction(LossFunctions.LossFunction.KL_DIVERGENCE)
                                                                                        .build(),
                                                        "layer1")
                                        .addLayer("out", new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                                        LossFunctions.LossFunction.MCXENT).nIn(3 + 3).nOut(3)
                                                                        .weightInit(WeightInit.DISTRIBUTION)
                                                                        .dist(new UniformDistribution(0, 1))
                                                                        .activation(Activation.SOFTMAX).build(),
                                                        "layer0", "layer2")
                                        .setOutputs("out").pretrain(true).backprop(false).build();


        ComputationGraph net = new ComputationGraph(conf);
        net.init();
        net.setListeners(new ScoreIterationListener(1));

        DataSetIterator iter = new IrisDataSetIterator(10, 150);
        net.fit(iter);
    }

    @Test
    public void testScoreExamples() {
        Nd4j.getRandom().setSeed(12345);
        int nIn = 5;
        int nOut = 6;
        ComputationGraphConfiguration conf =
                        new NeuralNetConfiguration.Builder().seed(12345).regularization(true).l1(0.01).l2(0.01)
                                        .learningRate(0.1).activation(Activation.TANH).weightInit(WeightInit.XAVIER)
                                        .graphBuilder().addInputs("in")
                                        .addLayer("0", new DenseLayer.Builder().nIn(nIn).nOut(20).build(), "in")
                                        .addLayer("1", new DenseLayer.Builder().nIn(20).nOut(30).build(), "0")
                                        .addLayer("2", new OutputLayer.Builder()
                                                        .lossFunction(LossFunctions.LossFunction.MSE).nIn(30).nOut(nOut)
                                                        .build(), "1")
                                        .setOutputs("2").build();

        ComputationGraphConfiguration confNoReg =
                        new NeuralNetConfiguration.Builder().seed(12345).learningRate(0.1).activation(Activation.TANH)
                                        .weightInit(WeightInit.XAVIER).graphBuilder().addInputs("in")
                                        .addLayer("0", new DenseLayer.Builder().nIn(nIn).nOut(20).build(), "in")
                                        .addLayer("1", new DenseLayer.Builder().nIn(20).nOut(30).build(), "0")
                                        .addLayer("2", new OutputLayer.Builder()
                                                        .lossFunction(LossFunctions.LossFunction.MSE).nIn(30).nOut(nOut)
                                                        .build(), "1")
                                        .setOutputs("2").build();


        ComputationGraph net = new ComputationGraph(conf);
        net.init();

        ComputationGraph netNoReg = new ComputationGraph(confNoReg);
        netNoReg.init();
        netNoReg.setParams(net.params().dup());

        //Score single example, and compare to scoreExamples:
        INDArray input = Nd4j.rand(3, nIn);
        INDArray output = Nd4j.rand(3, nOut);
        DataSet ds = new DataSet(input, output);

        INDArray scoresWithRegularization = net.scoreExamples(ds, true);
        INDArray scoresNoRegularization = net.scoreExamples(ds, false);

        assertArrayEquals(new int[] {3, 1}, scoresWithRegularization.shape());
        assertArrayEquals(new int[] {3, 1}, scoresNoRegularization.shape());

        for (int i = 0; i < 3; i++) {
            DataSet singleEx = new DataSet(input.getRow(i), output.getRow(i));
            double score = net.score(singleEx);
            double scoreNoReg = netNoReg.score(singleEx);

            double scoreUsingScoreExamples = scoresWithRegularization.getDouble(i);
            double scoreUsingScoreExamplesNoReg = scoresNoRegularization.getDouble(i);
            assertEquals(score, scoreUsingScoreExamples, 1e-4);
            assertEquals(scoreNoReg, scoreUsingScoreExamplesNoReg, 1e-4);
            assertTrue(scoreUsingScoreExamples > scoreUsingScoreExamplesNoReg); //Regularization term increases score

            //            System.out.println(score + "\t" + scoreUsingScoreExamples + "\t|\t" + scoreNoReg + "\t" + scoreUsingScoreExamplesNoReg);
        }
    }


    @Test
    public void testExternalErrors() {
        //Simple test: same network, but in one case: one less layer (the OutputLayer), where the epsilons are passed in externally
        // instead. Should get identical results

        Nd4j.getRandom().setSeed(12345);
        INDArray inData = Nd4j.rand(3, 10);
        INDArray outData = Nd4j.rand(3, 10);

        Nd4j.getRandom().setSeed(12345);
        ComputationGraphConfiguration standard = new NeuralNetConfiguration.Builder().learningRate(0.1)
                        .updater(Updater.SGD).seed(12345).graphBuilder().addInputs("in")
                        .addLayer("l0", new DenseLayer.Builder().nIn(10).nOut(10).build(), "in")
                        .addLayer("out", new OutputLayer.Builder().lossFunction(LossFunctions.LossFunction.MSE).nIn(10)
                                        .nOut(10).build(), "l0")
                        .setOutputs("out").pretrain(false).backprop(true).build();
        ComputationGraph s = new ComputationGraph(standard);
        s.init();


        Nd4j.getRandom().setSeed(12345);
        ComputationGraphConfiguration external = new NeuralNetConfiguration.Builder().learningRate(0.1)
                        .updater(Updater.SGD).seed(12345).graphBuilder().addInputs("in")
                        .addLayer("l0", new DenseLayer.Builder().nIn(10).nOut(10).build(), "in").setOutputs("l0")
                        .pretrain(false).backprop(true).build();

        ComputationGraph e = new ComputationGraph(external);
        e.init();

        s.setInputs(inData);
        s.setLabels(outData);
        s.computeGradientAndScore();
        Gradient sGrad = s.gradient();

        org.deeplearning4j.nn.layers.OutputLayer ol = (org.deeplearning4j.nn.layers.OutputLayer) s.getLayer(1);
        Pair<Gradient, INDArray> olPairStd = ol.backpropGradient(null);

        INDArray olEpsilon = olPairStd.getSecond();

        e.feedForward(inData, true);
        Gradient extErrorGrad = e.backpropGradient(olEpsilon);

        int nParamsDense = 10 * 10 + 10;
        assertEquals(sGrad.gradient().get(NDArrayIndex.point(0), NDArrayIndex.interval(0, nParamsDense)),
                        extErrorGrad.gradient());

    }

    @Test
    public void testGradientUpdate() {
        DataSetIterator iter = new IrisDataSetIterator(1, 1);

        Gradient expectedGradient = new DefaultGradient();
        expectedGradient.setGradientFor("first_W", Nd4j.ones(4, 5));
        expectedGradient.setGradientFor("first_b", Nd4j.ones(1, 5));
        expectedGradient.setGradientFor("output_W", Nd4j.ones(5, 3));
        expectedGradient.setGradientFor("output_b", Nd4j.ones(1, 3));

        ComputationGraphConfiguration conf = new NeuralNetConfiguration.Builder()
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).graphBuilder()
                        .addInputs("input").addLayer("first", new DenseLayer.Builder().nIn(4).nOut(5).build(), "input")
                        .addLayer("output", new OutputLayer.Builder().nIn(5).nOut(3).build(), "first")
                        .setOutputs("output").pretrain(false).backprop(true).build();

        ComputationGraph net = new ComputationGraph(conf);
        net.init();
        net.fit(iter.next());
        Gradient actualGradient = net.gradient;
        assertNotEquals(expectedGradient.getGradientFor("first_W"), actualGradient.getGradientFor("first_W"));

        net.update(expectedGradient);
        actualGradient = net.gradient;
        assertEquals(expectedGradient.getGradientFor("first_W"), actualGradient.getGradientFor("first_W"));

        // Update params with set
        net.setParam("first_W", Nd4j.ones(4, 5));
        net.setParam("first_b", Nd4j.ones(1, 5));
        net.setParam("output_W", Nd4j.ones(5, 3));
        net.setParam("output_b", Nd4j.ones(1, 3));
        INDArray actualParams = net.params();

        // Confirm params
        assertEquals(Nd4j.ones(1, 43), actualParams);

        net.update(expectedGradient);
        actualParams = net.params();
        assertEquals(Nd4j.ones(1, 43).addi(1), actualParams);
    }


    @Test
    public void testCnnFlatInputType1() {

        //First: check conv input type. Expect: no preprocessor, nIn set appropriately
        ComputationGraphConfiguration conf = new NeuralNetConfiguration.Builder().graphBuilder().addInputs("in")
                        .setInputTypes(InputType.convolutional(10, 8, 3))
                        .addLayer("layer",
                                        new ConvolutionLayer.Builder().kernelSize(2, 2).padding(0, 0).stride(1, 1)
                                                        .build(),
                                        "in")
                        .addLayer("out", new OutputLayer.Builder().nOut(10).build(), "layer").setOutputs("out")
                        .pretrain(false).backprop(true).build();

        LayerVertex lv = (LayerVertex) conf.getVertices().get("layer");
        FeedForwardLayer l = ((FeedForwardLayer) (lv).getLayerConf().getLayer());
        assertEquals(3, l.getNIn());
        assertNull(lv.getPreProcessor());

        //Check the equivalent config, but with flat conv data input instead
        //In this case, the only difference should be the addition of a preprocessor
        //First: check conv input type. Expect: no preprocessor, nIn set appropriately
        conf = new NeuralNetConfiguration.Builder().graphBuilder().addInputs("in")
                        .setInputTypes(InputType.convolutionalFlat(10, 8, 3))
                        .addLayer("layer",
                                        new ConvolutionLayer.Builder().kernelSize(2, 2).padding(0, 0).stride(1, 1)
                                                        .build(),
                                        "in")
                        .addLayer("out", new OutputLayer.Builder().nOut(10).build(), "layer").setOutputs("out")
                        .pretrain(false).backprop(true).build();

        lv = (LayerVertex) conf.getVertices().get("layer");
        l = ((FeedForwardLayer) (lv).getLayerConf().getLayer());
        assertEquals(3, l.getNIn());
        assertNotNull(lv.getPreProcessor());
        InputPreProcessor preProcessor = lv.getPreProcessor();
        assertTrue(preProcessor instanceof FeedForwardToCnnPreProcessor);
        FeedForwardToCnnPreProcessor preproc = (FeedForwardToCnnPreProcessor) preProcessor;
        assertEquals(10, preproc.getInputHeight());
        assertEquals(8, preproc.getInputWidth());
        assertEquals(3, preproc.getNumChannels());


        //Finally, check configuration with a subsampling layer
        conf = new NeuralNetConfiguration.Builder().graphBuilder().addInputs("in")
                        .setInputTypes(InputType.convolutionalFlat(10, 8, 3))
                        .addLayer("l0", new SubsamplingLayer.Builder().kernelSize(2, 2).stride(1, 1).padding(0, 0)
                                        .build(), "in")
                        .addLayer("layer",
                                        new ConvolutionLayer.Builder().kernelSize(2, 2).padding(0, 0).stride(1, 1)
                                                        .build(),
                                        "l0")
                        .addLayer("out", new OutputLayer.Builder().nOut(10).build(), "layer").setOutputs("out")
                        .pretrain(false).backprop(true).build();

        //Check subsampling layer:
        lv = (LayerVertex) conf.getVertices().get("l0");
        SubsamplingLayer sl = ((SubsamplingLayer) (lv).getLayerConf().getLayer());
        assertNotNull(lv.getPreProcessor());
        preProcessor = lv.getPreProcessor();
        assertTrue(preProcessor instanceof FeedForwardToCnnPreProcessor);
        preproc = (FeedForwardToCnnPreProcessor) preProcessor;
        assertEquals(10, preproc.getInputHeight());
        assertEquals(8, preproc.getInputWidth());
        assertEquals(3, preproc.getNumChannels());
        //Check dense layer
        lv = (LayerVertex) conf.getVertices().get("layer");
        l = ((FeedForwardLayer) (lv).getLayerConf().getLayer());
        assertEquals(3, l.getNIn());
        assertNull(lv.getPreProcessor());

    }

    @Test
    public void testCGEvaluation() {

        Nd4j.getRandom().setSeed(12345);
        ComputationGraphConfiguration configuration = getIrisGraphConfiguration();
        ComputationGraph graph = new ComputationGraph(configuration);
        graph.init();

        Nd4j.getRandom().setSeed(12345);
        MultiLayerConfiguration mlnConfig = getIrisMLNConfiguration();
        MultiLayerNetwork net = new MultiLayerNetwork(mlnConfig);
        net.init();

        DataSetIterator iris = new IrisDataSetIterator(75, 150);

        net.fit(iris);
        iris.reset();
        graph.fit(iris);

        iris.reset();
        Evaluation evalExpected = net.evaluate(iris);
        iris.reset();
        Evaluation evalActual = graph.evaluate(iris);

        assertEquals(evalExpected.accuracy(), evalActual.accuracy(), 0e-4);
    }

    @Test
    public void testApplyingPreTrainConfigAndParams() {
        int nIn = 10;
        int nOut = 10;

        // Test pretrain true
        ComputationGraph rbmPre = getRBMModel(true, nIn, nOut);
        assertTrue(rbmPre.getConfiguration().isPretrain()); // check on the graph
        assertTrue(rbmPre.conf().isPretrain()); // check on the network
        assertTrue(rbmPre.getLayer("firstLayer").conf().isPretrain()); // check on the layer
        assertFalse(rbmPre.getLayer("outputLayer").conf().isPretrain()); // check on the layer
        int actualNP = rbmPre.numParams();
        assertEquals(2 * (nIn * nOut + nOut) + nIn, actualNP);
        INDArray params = rbmPre.params();
        assertEquals(params.length(), actualNP);
        Map<String, INDArray> paramTable = rbmPre.paramTable();
        assertTrue(paramTable.containsKey("firstLayer_vb"));
        assertFalse(paramTable.containsKey("outputLayer_vb"));
        rbmPre.setParam("firstLayer_vb", Nd4j.ones(10));
        params = rbmPre.getParam("firstLayer_vb");
        assertEquals(Nd4j.ones(10), params);


        // Test pretrain false
        ComputationGraph rbmNoPre = getRBMModel(false, nIn, nOut);
        assertFalse(rbmNoPre.conf().isPretrain());
        assertFalse(rbmNoPre.getConfiguration().isPretrain());
        assertFalse(rbmNoPre.getLayer("firstLayer").conf().isPretrain());
        assertFalse(rbmNoPre.getLayer("outputLayer").conf().isPretrain()); // check on the layer
        actualNP = rbmNoPre.numParams();
        assertEquals(2 * (nIn * nOut + nOut) + nIn, actualNP);
        params = rbmNoPre.params();
        assertEquals(params.length(), actualNP);
        paramTable = rbmNoPre.paramTable();
        assertTrue(paramTable.containsKey("firstLayer_vb"));
        assertFalse(paramTable.containsKey("outputLayer_vb"));

    }

    @Test
    public void testOptimizationAlgorithmsSearchBasic() {
        DataSetIterator iter = new IrisDataSetIterator(1, 1);

        OptimizationAlgorithm[] oas = new OptimizationAlgorithm[] {OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT,
                        OptimizationAlgorithm.LINE_GRADIENT_DESCENT, OptimizationAlgorithm.CONJUGATE_GRADIENT,
                        OptimizationAlgorithm.LBFGS};

        for (OptimizationAlgorithm oa : oas) {
            System.out.println(oa);
            ComputationGraphConfiguration conf =
                            new NeuralNetConfiguration.Builder().optimizationAlgo(oa).iterations(1).graphBuilder()
                                            .addInputs("input")
                                            .addLayer("first", new DenseLayer.Builder().nIn(4).nOut(5).build(), "input")
                                            .addLayer("output", new OutputLayer.Builder().nIn(5).nOut(3).build(),
                                                            "first")
                                            .setOutputs("output").pretrain(false).backprop(true).build();

            ComputationGraph net = new ComputationGraph(conf);
            net.init();
            net.fit(iter.next());

        }
    }

    @Test
    public void testIterationCountAndPresistence() throws IOException {
        Nd4j.getRandom().setSeed(123);
        ComputationGraphConfiguration conf = new NeuralNetConfiguration.Builder()
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).iterations(1).seed(123)
                        .graphBuilder().addInputs("in")
                        .addLayer("0", new DenseLayer.Builder().nIn(4).nOut(3).weightInit(WeightInit.XAVIER)
                                        .activation(Activation.TANH).build(), "in")
                        .addLayer("1", new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                        LossFunctions.LossFunction.MCXENT).activation(Activation.SOFTMAX).nIn(3).nOut(3)
                                                        .build(),
                                        "0")
                        .setOutputs("1").backprop(true).pretrain(false).build();


        ComputationGraph network = new ComputationGraph(conf);
        network.init();

        DataSetIterator iter = new IrisDataSetIterator(50, 150);

        assertEquals(0, network.getConfiguration().getIterationCount());
        network.fit(iter);
        assertEquals(3, network.getConfiguration().getIterationCount());
        iter.reset();
        network.fit(iter);
        assertEquals(6, network.getConfiguration().getIterationCount());
        iter.reset();
        network.fit(iter.next());
        assertEquals(7, network.getConfiguration().getIterationCount());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ModelSerializer.writeModel(network, baos, true);
        byte[] asBytes = baos.toByteArray();

        ByteArrayInputStream bais = new ByteArrayInputStream(asBytes);
        ComputationGraph net = ModelSerializer.restoreComputationGraph(bais, true);
        assertEquals(7, net.getConfiguration().getIterationCount());
    }

    @Test
    public void printSummary() {
        NeuralNetConfiguration.Builder overallConf = new NeuralNetConfiguration.Builder().learningRate(0.1)
                        .activation(Activation.IDENTITY)
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).updater(Updater.SGD);

        ComputationGraphConfiguration conf = overallConf.graphBuilder().addInputs("inCentre", "inRight")
                        .addLayer("denseCentre0", new DenseLayer.Builder().nIn(10).nOut(9).build(), "inCentre")
                        .addLayer("denseCentre1", new DenseLayer.Builder().nIn(9).nOut(8).build(), "denseCentre0")
                        .addLayer("denseCentre2", new DenseLayer.Builder().nIn(8).nOut(7).build(), "denseCentre1")
                        .addLayer("denseCentre3", new DenseLayer.Builder().nIn(7).nOut(7).build(), "denseCentre2")
                        .addLayer("outCentre",
                                        new OutputLayer.Builder(LossFunctions.LossFunction.MSE).nIn(7).nOut(4).build(),
                                        "denseCentre3")
                        .addVertex("subsetLeft", new SubsetVertex(0, 3), "denseCentre1")
                        .addLayer("denseLeft0", new DenseLayer.Builder().nIn(4).nOut(5).build(), "subsetLeft")
                        .addLayer("outLeft",
                                        new OutputLayer.Builder(LossFunctions.LossFunction.MSE).nIn(5).nOut(6).build(),
                                        "denseLeft0")
                        .addLayer("denseRight", new DenseLayer.Builder().nIn(7).nOut(7).build(), "denseCentre2")
                        .addLayer("denseRight0", new DenseLayer.Builder().nIn(2).nOut(3).build(), "inRight")
                        .addVertex("mergeRight", new MergeVertex(), "denseRight", "denseRight0")
                        .addLayer("denseRight1", new DenseLayer.Builder().nIn(10).nOut(5).build(), "mergeRight")
                        .addLayer("outRight",
                                        new OutputLayer.Builder(LossFunctions.LossFunction.MSE).nIn(5).nOut(5).build(),
                                        "denseRight1")
                        .setOutputs("outLeft", "outCentre", "outRight").build();

        ComputationGraph modelToTune = new ComputationGraph(conf);
        modelToTune.init();
        System.out.println(modelToTune.summary());

        ComputationGraph modelNow =
                        new TransferLearning.GraphBuilder(modelToTune).setFeatureExtractor("denseCentre2").build();
        System.out.println(modelNow.summary());
    }
}
