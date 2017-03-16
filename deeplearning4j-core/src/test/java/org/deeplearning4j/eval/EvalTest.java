/*-
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.eval;

import org.datavec.api.records.metadata.RecordMetaData;
import org.datavec.api.records.reader.RecordReader;
import org.datavec.api.records.reader.impl.csv.CSVRecordReader;
import org.datavec.api.split.FileSplit;
import org.deeplearning4j.datasets.datavec.RecordReaderDataSetIterator;
import org.deeplearning4j.datasets.iterator.impl.IrisDataSetIterator;
import org.deeplearning4j.datasets.iterator.impl.ListDataSetIterator;
import org.deeplearning4j.eval.meta.Prediction;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.api.IterationListener;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.junit.Test;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.SplitTestAndTrain;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.dataset.api.preprocessor.NormalizerStandardize;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.INDArrayIndex;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.io.ClassPathResource;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.nd4j.linalg.util.FeatureUtil;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Created by agibsonccc on 12/22/14.
 */
public class EvalTest {


    @Test
    public void testEval() {
        int classNum = 5;
        Evaluation eval = new Evaluation(classNum);

        // Testing the edge case when some classes do not have true positive
        INDArray trueOutcome = FeatureUtil.toOutcomeVector(0, 5); //[1,0,0,0,0]
        INDArray predictedOutcome = FeatureUtil.toOutcomeVector(0, 5); //[1,0,0,0,0]
        eval.eval(trueOutcome, predictedOutcome);
        assertEquals(1, eval.classCount(0));
        assertEquals(1.0, eval.f1(), 1e-1);

        // Testing more than one sample. eval() does not reset the Evaluation instance
        INDArray trueOutcome2 = FeatureUtil.toOutcomeVector(1, 5); //[0,1,0,0,0]
        INDArray predictedOutcome2 = FeatureUtil.toOutcomeVector(0, 5); //[1,0,0,0,0]
        eval.eval(trueOutcome2, predictedOutcome2);
        // Verified with sklearn in Python
        // from sklearn.metrics import classification_report
        // classification_report(['a', 'a'], ['a', 'b'], labels=['a', 'b', 'c', 'd', 'e'])
        assertEquals(eval.f1(), 0.6, 1e-1);
        // The first entry is 0 label
        assertEquals(1, eval.classCount(0));
        // The first entry is 1 label
        assertEquals(1, eval.classCount(1));
        // Class 0: one positive, one negative -> (one true positive, one false positive); no true/false negatives
        assertEquals(1, eval.positive().get(0), 0);
        assertEquals(1, eval.negative().get(0), 0);
        assertEquals(1, eval.truePositives().get(0), 0);
        assertEquals(1, eval.falsePositives().get(0), 0);
        assertEquals(0, eval.trueNegatives().get(0), 0);
        assertEquals(0, eval.falseNegatives().get(0), 0);


        // The rest are negative
        assertEquals(1, eval.negative().get(0), 0);
        // 2 rows and only the first is correct
        assertEquals(0.5, eval.accuracy(), 0);
    }

    @Test
    public void testEval2() {

        //Confusion matrix:
        //actual 0      20      3
        //actual 1      10      5

        Evaluation evaluation = new Evaluation(Arrays.asList("class0", "class1"));
        INDArray predicted0 = Nd4j.create(new double[] {1, 0});
        INDArray predicted1 = Nd4j.create(new double[] {0, 1});
        INDArray actual0 = Nd4j.create(new double[] {1, 0});
        INDArray actual1 = Nd4j.create(new double[] {0, 1});
        for (int i = 0; i < 20; i++) {
            evaluation.eval(actual0, predicted0);
        }

        for (int i = 0; i < 3; i++) {
            evaluation.eval(actual0, predicted1);
        }

        for (int i = 0; i < 10; i++) {
            evaluation.eval(actual1, predicted0);
        }

        for (int i = 0; i < 5; i++) {
            evaluation.eval(actual1, predicted1);
        }

        assertEquals(20, evaluation.truePositives().get(0), 0);
        assertEquals(3, evaluation.falseNegatives().get(0), 0);
        assertEquals(10, evaluation.falsePositives().get(0), 0);
        assertEquals(5, evaluation.trueNegatives().get(0), 0);

        assertEquals((20.0 + 5) / (20 + 3 + 10 + 5), evaluation.accuracy(), 1e-6);

        System.out.println(evaluation.confusionToString());
    }

    @Test
    public void testStringListLabels() {
        INDArray trueOutcome = FeatureUtil.toOutcomeVector(0, 2);
        INDArray predictedOutcome = FeatureUtil.toOutcomeVector(0, 2);

        List<String> labelsList = new ArrayList<>();
        labelsList.add("hobbs");
        labelsList.add("cal");

        Evaluation eval = new Evaluation(labelsList);

        eval.eval(trueOutcome, predictedOutcome);
        assertEquals(1, eval.classCount(0));
        assertEquals(labelsList.get(0), eval.getClassLabel(0));

    }

    @Test
    public void testStringHashLabels() {
        INDArray trueOutcome = FeatureUtil.toOutcomeVector(0, 2);
        INDArray predictedOutcome = FeatureUtil.toOutcomeVector(0, 2);

        Map<Integer, String> labelsMap = new HashMap<>();
        labelsMap.put(0, "hobbs");
        labelsMap.put(1, "cal");

        Evaluation eval = new Evaluation(labelsMap);

        eval.eval(trueOutcome, predictedOutcome);
        assertEquals(1, eval.classCount(0));
        assertEquals(labelsMap.get(0), eval.getClassLabel(0));

    }


    @Test
    public void testIris() {

        // Network config
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()

                        .optimizationAlgo(OptimizationAlgorithm.LINE_GRADIENT_DESCENT).iterations(1).seed(42)
                        .learningRate(1e-6).list()
                        .layer(0, new DenseLayer.Builder().nIn(4).nOut(2).activation(Activation.TANH)
                                        .weightInit(WeightInit.XAVIER).build())
                        .layer(1, new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                        LossFunctions.LossFunction.MCXENT).nIn(2).nOut(3).weightInit(WeightInit.XAVIER)
                                                        .activation(Activation.SOFTMAX).build())

                        .build();

        // Instantiate model
        MultiLayerNetwork model = new MultiLayerNetwork(conf);
        model.init();
        model.setListeners(Arrays.asList((IterationListener) new ScoreIterationListener(1)));

        // Train-test split
        DataSetIterator iter = new IrisDataSetIterator(150, 150);
        DataSet next = iter.next();
        next.shuffle();
        SplitTestAndTrain trainTest = next.splitTestAndTrain(5, new Random(42));

        // Train
        DataSet train = trainTest.getTrain();
        train.normalizeZeroMeanZeroUnitVariance();

        // Test
        DataSet test = trainTest.getTest();
        test.normalizeZeroMeanZeroUnitVariance();
        INDArray testFeature = test.getFeatureMatrix();
        INDArray testLabel = test.getLabels();

        // Fitting model
        model.fit(train);
        // Get predictions from test feature
        INDArray testPredictedLabel = model.output(testFeature);

        // Eval with class number
        Evaluation eval = new Evaluation(3); //// Specify class num here
        eval.eval(testLabel, testPredictedLabel);
        double eval1F1 = eval.f1();
        double eval1Acc = eval.accuracy();

        // Eval without class number
        Evaluation eval2 = new Evaluation(); //// No class num
        eval2.eval(testLabel, testPredictedLabel);
        double eval2F1 = eval2.f1();
        double eval2Acc = eval2.accuracy();

        //Assert the two implementations give same f1 and accuracy (since one batch)
        assertTrue(eval1F1 == eval2F1 && eval1Acc == eval2Acc);

        Evaluation evalViaMethod = model.evaluate(new ListDataSetIterator(Collections.singletonList(test)));
        checkEvaluationEquality(eval, evalViaMethod);

        System.out.println(eval.getConfusionMatrix().toString());
        System.out.println(eval.getConfusionMatrix().toCSV());
        System.out.println(eval.getConfusionMatrix().toHTML());

        System.out.println(eval.confusionToString());
    }

    @Test
    public void testEvalMasking() {
        int miniBatch = 5;
        int nOut = 3;
        int tsLength = 6;

        INDArray labels = Nd4j.zeros(miniBatch, nOut, tsLength);
        INDArray predicted = Nd4j.zeros(miniBatch, nOut, tsLength);

        Nd4j.getRandom().setSeed(12345);
        Random r = new Random(12345);
        for (int i = 0; i < miniBatch; i++) {
            for (int j = 0; j < tsLength; j++) {
                INDArray rand = Nd4j.rand(1, nOut);
                rand.divi(rand.sumNumber());
                predicted.put(new INDArrayIndex[] {NDArrayIndex.point(i), NDArrayIndex.all(), NDArrayIndex.point(j)},
                                rand);
                int idx = r.nextInt(nOut);
                labels.putScalar(new int[] {i, idx, j}, 1.0);
            }
        }

        //Create a longer labels/predicted with mask for first and last time step
        //Expect masked evaluation to be identical to original evaluation
        INDArray labels2 = Nd4j.zeros(miniBatch, nOut, tsLength + 2);
        labels2.put(new INDArrayIndex[] {NDArrayIndex.all(), NDArrayIndex.all(),
                        NDArrayIndex.interval(1, tsLength + 1)}, labels);
        INDArray predicted2 = Nd4j.zeros(miniBatch, nOut, tsLength + 2);
        predicted2.put(new INDArrayIndex[] {NDArrayIndex.all(), NDArrayIndex.all(),
                        NDArrayIndex.interval(1, tsLength + 1)}, predicted);

        INDArray labelsMask = Nd4j.ones(miniBatch, tsLength + 2);
        for (int i = 0; i < miniBatch; i++) {
            labelsMask.putScalar(new int[] {i, 0}, 0.0);
            labelsMask.putScalar(new int[] {i, tsLength + 1}, 0.0);
        }

        Evaluation evaluation = new Evaluation();
        evaluation.evalTimeSeries(labels, predicted);

        Evaluation evaluation2 = new Evaluation();
        evaluation2.evalTimeSeries(labels2, predicted2, labelsMask);

        System.out.println(evaluation.stats());
        System.out.println(evaluation2.stats());

        assertEquals(evaluation.accuracy(), evaluation2.accuracy(), 1e-12);
        assertEquals(evaluation.f1(), evaluation2.f1(), 1e-12);

        assertMapEquals(evaluation.falsePositives(), evaluation2.falsePositives());
        assertMapEquals(evaluation.falseNegatives(), evaluation2.falseNegatives());
        assertMapEquals(evaluation.truePositives(), evaluation2.truePositives());
        assertMapEquals(evaluation.trueNegatives(), evaluation2.trueNegatives());

        for (int i = 0; i < nOut; i++)
            assertEquals(evaluation.classCount(i), evaluation2.classCount(i));
    }

    private static void assertMapEquals(Map<Integer, Integer> first, Map<Integer, Integer> second) {
        assertEquals(first.keySet(), second.keySet());
        for (Integer i : first.keySet()) {
            assertEquals(first.get(i), second.get(i));
        }
    }

    @Test
    public void testFalsePerfectRecall() {
        int testSize = 100;
        int numClasses = 5;
        int winner = 1;
        int seed = 241;

        INDArray labels = Nd4j.zeros(testSize, numClasses);
        INDArray predicted = Nd4j.zeros(testSize, numClasses);

        Nd4j.getRandom().setSeed(seed);
        Random r = new Random(seed);

        //Modelling the situation when system predicts the same class every time
        for (int i = 0; i < testSize; i++) {
            //Generating random prediction but with a guaranteed winner
            INDArray rand = Nd4j.rand(1, numClasses);
            rand.put(0, winner, rand.sumNumber());
            rand.divi(rand.sumNumber());
            predicted.put(new INDArrayIndex[] {NDArrayIndex.point(i), NDArrayIndex.all()}, rand);
            //Generating random label
            int label = r.nextInt(numClasses);
            labels.putScalar(new int[] {i, label}, 1.0);
        }

        //Explicitly specify the amount of classes
        Evaluation eval = new Evaluation(numClasses);
        eval.eval(labels, predicted);

        //For sure we shouldn't arrive at 100% recall unless we guessed everything right for every class
        assertNotEquals(1.0, eval.recall());
    }

    @Test
    public void testEvaluationMerging() {

        int nRows = 20;
        int nCols = 3;

        Random r = new Random(12345);
        INDArray actual = Nd4j.create(nRows, nCols);
        INDArray predicted = Nd4j.create(nRows, nCols);
        for (int i = 0; i < nRows; i++) {
            int x1 = r.nextInt(nCols);
            int x2 = r.nextInt(nCols);
            actual.putScalar(new int[] {i, x1}, 1.0);
            predicted.putScalar(new int[] {i, x2}, 1.0);
        }

        Evaluation evalExpected = new Evaluation();
        evalExpected.eval(actual, predicted);


        //Now: split into 3 separate evaluation objects -> expect identical values after merging
        Evaluation eval1 = new Evaluation();
        eval1.eval(actual.get(NDArrayIndex.interval(0, 5), NDArrayIndex.all()),
                        predicted.get(NDArrayIndex.interval(0, 5), NDArrayIndex.all()));

        Evaluation eval2 = new Evaluation();
        eval2.eval(actual.get(NDArrayIndex.interval(5, 10), NDArrayIndex.all()),
                        predicted.get(NDArrayIndex.interval(5, 10), NDArrayIndex.all()));

        Evaluation eval3 = new Evaluation();
        eval3.eval(actual.get(NDArrayIndex.interval(10, nRows), NDArrayIndex.all()),
                        predicted.get(NDArrayIndex.interval(10, nRows), NDArrayIndex.all()));

        eval1.merge(eval2);
        eval1.merge(eval3);

        checkEvaluationEquality(evalExpected, eval1);


        //Next: check evaluation merging with empty, and empty merging with non-empty
        eval1 = new Evaluation();
        eval1.eval(actual.get(NDArrayIndex.interval(0, 5), NDArrayIndex.all()),
                        predicted.get(NDArrayIndex.interval(0, 5), NDArrayIndex.all()));

        Evaluation evalInitiallyEmpty = new Evaluation();
        evalInitiallyEmpty.merge(eval1);
        evalInitiallyEmpty.merge(eval2);
        evalInitiallyEmpty.merge(eval3);
        checkEvaluationEquality(evalExpected, evalInitiallyEmpty);

        eval1.merge(new Evaluation());
        eval1.merge(eval2);
        eval1.merge(new Evaluation());
        eval1.merge(eval3);
        checkEvaluationEquality(evalExpected, eval1);
    }

    private static void checkEvaluationEquality(Evaluation evalExpected, Evaluation evalActual) {
        assertEquals(evalExpected.accuracy(), evalActual.accuracy(), 1e-3);
        assertEquals(evalExpected.f1(), evalActual.f1(), 1e-3);
        assertEquals(evalExpected.getNumRowCounter(), evalActual.getNumRowCounter(), 1e-3);
        assertMapEquals(evalExpected.falseNegatives(), evalActual.falseNegatives());
        assertMapEquals(evalExpected.falsePositives(), evalActual.falsePositives());
        assertMapEquals(evalExpected.trueNegatives(), evalActual.trueNegatives());
        assertMapEquals(evalExpected.truePositives(), evalActual.truePositives());
        assertEquals(evalExpected.precision(), evalActual.precision(), 1e-3);
        assertEquals(evalExpected.recall(), evalActual.recall(), 1e-3);
        assertEquals(evalExpected.falsePositiveRate(), evalActual.falsePositiveRate(), 1e-3);
        assertEquals(evalExpected.falseNegativeRate(), evalActual.falseNegativeRate(), 1e-3);
        assertEquals(evalExpected.falseAlarmRate(), evalActual.falseAlarmRate(), 1e-3);
        assertEquals(evalExpected.getConfusionMatrix(), evalActual.getConfusionMatrix());
    }


    @Test
    public void testSingleClassBinaryClassification() {

        Evaluation eval = new Evaluation(1);

        INDArray zero = Nd4j.create(1);
        INDArray one = Nd4j.ones(1);

        //One incorrect, three correct
        eval.eval(one, zero);
        eval.eval(one, one);
        eval.eval(one, one);
        eval.eval(zero, zero);

        System.out.println(eval.stats());

        assertEquals(0.75, eval.accuracy(), 1e-6);
        assertEquals(4, eval.getNumRowCounter());

        assertEquals(1, (int) eval.truePositives().get(0));
        assertEquals(2, (int) eval.truePositives().get(1));
        assertEquals(1, (int) eval.falseNegatives().get(1));
    }

    @Test
    public void testEvalInvalid() {
        Evaluation e = new Evaluation(5);
        e.eval(0, 1);
        e.eval(1, 0);
        e.eval(1, 1);

        System.out.println(e.stats());

        char c = "\uFFFD".toCharArray()[0];
        System.out.println(c);

        assertFalse(e.stats().contains("\uFFFD"));
    }

    @Test
    public void testEvalMethods() {
        //Check eval(int,int) vs. eval(INDArray,INDArray)

        Evaluation e1 = new Evaluation(4);
        Evaluation e2 = new Evaluation(4);

        INDArray i0 = Nd4j.create(new double[] {1, 0, 0, 0});
        INDArray i1 = Nd4j.create(new double[] {0, 1, 0, 0});
        INDArray i2 = Nd4j.create(new double[] {0, 0, 1, 0});
        INDArray i3 = Nd4j.create(new double[] {0, 0, 0, 1});

        e1.eval(i0, i0); //order: actual, predicted
        e2.eval(0, 0); //order: predicted, actual
        e1.eval(i0, i2);
        e2.eval(2, 0);
        e1.eval(i0, i2);
        e2.eval(2, 0);
        e1.eval(i1, i2);
        e2.eval(2, 1);
        e1.eval(i3, i3);
        e2.eval(3, 3);
        e1.eval(i3, i0);
        e2.eval(0, 3);
        e1.eval(i3, i0);
        e2.eval(0, 3);

        ConfusionMatrix<Integer> cm = e1.getConfusionMatrix();
        assertEquals(1, cm.getCount(0, 0)); //Order: actual, predicted
        assertEquals(2, cm.getCount(0, 2));
        assertEquals(1, cm.getCount(1, 2));
        assertEquals(1, cm.getCount(3, 3));
        assertEquals(2, cm.getCount(3, 0));

        System.out.println(e1.stats());
        System.out.println(e2.stats());

        assertEquals(e1.stats(), e2.stats());
    }


    @Test
    public void testTopNAccuracy() {

        Evaluation e = new Evaluation(null, 3);

        INDArray i0 = Nd4j.create(new double[] {1, 0, 0, 0, 0});
        INDArray i1 = Nd4j.create(new double[] {0, 1, 0, 0, 0});

        INDArray p0_0 = Nd4j.create(new double[] {0.8, 0.05, 0.05, 0.05, 0.05}); //class 0: highest prob
        INDArray p0_1 = Nd4j.create(new double[] {0.4, 0.45, 0.05, 0.05, 0.05}); //class 0: 2nd highest prob
        INDArray p0_2 = Nd4j.create(new double[] {0.1, 0.45, 0.35, 0.05, 0.05}); //class 0: 3rd highest prob
        INDArray p0_3 = Nd4j.create(new double[] {0.1, 0.40, 0.30, 0.15, 0.05}); //class 0: 4th highest prob

        INDArray p1_0 = Nd4j.create(new double[] {0.05, 0.80, 0.05, 0.05, 0.05}); //class 1: highest prob
        INDArray p1_1 = Nd4j.create(new double[] {0.45, 0.40, 0.05, 0.05, 0.05}); //class 1: 2nd highest prob
        INDArray p1_2 = Nd4j.create(new double[] {0.35, 0.10, 0.45, 0.05, 0.05}); //class 1: 3rd highest prob
        INDArray p1_3 = Nd4j.create(new double[] {0.40, 0.10, 0.30, 0.15, 0.05}); //class 1: 4th highest prob


        //                                              Correct     TopNCorrect     Total
        e.eval(i0, p0_0); //  1           1               1
        assertEquals(1.0, e.accuracy(), 1e-6);
        assertEquals(1.0, e.topNAccuracy(), 1e-6);
        assertEquals(1, e.getTopNCorrectCount());
        assertEquals(1, e.getTopNTotalCount());
        e.eval(i0, p0_1); //  1           2               2
        assertEquals(0.5, e.accuracy(), 1e-6);
        assertEquals(1.0, e.topNAccuracy(), 1e-6);
        assertEquals(2, e.getTopNCorrectCount());
        assertEquals(2, e.getTopNTotalCount());
        e.eval(i0, p0_2); //  1           3               3
        assertEquals(1.0 / 3, e.accuracy(), 1e-6);
        assertEquals(1.0, e.topNAccuracy(), 1e-6);
        assertEquals(3, e.getTopNCorrectCount());
        assertEquals(3, e.getTopNTotalCount());
        e.eval(i0, p0_3); //  1           3               4
        assertEquals(0.25, e.accuracy(), 1e-6);
        assertEquals(0.75, e.topNAccuracy(), 1e-6);
        assertEquals(3, e.getTopNCorrectCount());
        assertEquals(4, e.getTopNTotalCount());

        e.eval(i1, p1_0); //  2           4               5
        assertEquals(2.0 / 5, e.accuracy(), 1e-6);
        assertEquals(4.0 / 5, e.topNAccuracy(), 1e-6);
        e.eval(i1, p1_1); //  2           5               6
        assertEquals(2.0 / 6, e.accuracy(), 1e-6);
        assertEquals(5.0 / 6, e.topNAccuracy(), 1e-6);
        e.eval(i1, p1_2); //  2           6               7
        assertEquals(2.0 / 7, e.accuracy(), 1e-6);
        assertEquals(6.0 / 7, e.topNAccuracy(), 1e-6);
        e.eval(i1, p1_3); //  2           6               8
        assertEquals(2.0 / 8, e.accuracy(), 1e-6);
        assertEquals(6.0 / 8, e.topNAccuracy(), 1e-6);
        assertEquals(6, e.getTopNCorrectCount());
        assertEquals(8, e.getTopNTotalCount());

        System.out.println(e.stats());
    }


    @Test
    public void testTopNAccuracyMerging() {

        Evaluation e1 = new Evaluation(null, 3);
        Evaluation e2 = new Evaluation(null, 3);

        INDArray i0 = Nd4j.create(new double[] {1, 0, 0, 0, 0});
        INDArray i1 = Nd4j.create(new double[] {0, 1, 0, 0, 0});

        INDArray p0_0 = Nd4j.create(new double[] {0.8, 0.05, 0.05, 0.05, 0.05}); //class 0: highest prob
        INDArray p0_1 = Nd4j.create(new double[] {0.4, 0.45, 0.05, 0.05, 0.05}); //class 0: 2nd highest prob
        INDArray p0_2 = Nd4j.create(new double[] {0.1, 0.45, 0.35, 0.05, 0.05}); //class 0: 3rd highest prob
        INDArray p0_3 = Nd4j.create(new double[] {0.1, 0.40, 0.30, 0.15, 0.05}); //class 0: 4th highest prob

        INDArray p1_0 = Nd4j.create(new double[] {0.05, 0.80, 0.05, 0.05, 0.05}); //class 1: highest prob
        INDArray p1_1 = Nd4j.create(new double[] {0.45, 0.40, 0.05, 0.05, 0.05}); //class 1: 2nd highest prob
        INDArray p1_2 = Nd4j.create(new double[] {0.35, 0.10, 0.45, 0.05, 0.05}); //class 1: 3rd highest prob
        INDArray p1_3 = Nd4j.create(new double[] {0.40, 0.10, 0.30, 0.15, 0.05}); //class 1: 4th highest prob


        //                                              Correct     TopNCorrect     Total
        e1.eval(i0, p0_0); //  1           1               1
        e1.eval(i0, p0_1); //  1           2               2
        e1.eval(i0, p0_2); //  1           3               3
        e1.eval(i0, p0_3); //  1           3               4
        assertEquals(0.25, e1.accuracy(), 1e-6);
        assertEquals(0.75, e1.topNAccuracy(), 1e-6);
        assertEquals(3, e1.getTopNCorrectCount());
        assertEquals(4, e1.getTopNTotalCount());

        e2.eval(i1, p1_0); //  1           1               1
        e2.eval(i1, p1_1); //  1           2               2
        e2.eval(i1, p1_2); //  1           3               3
        e2.eval(i1, p1_3); //  1           3               4
        assertEquals(1.0 / 4, e2.accuracy(), 1e-6);
        assertEquals(3.0 / 4, e2.topNAccuracy(), 1e-6);
        assertEquals(3, e2.getTopNCorrectCount());
        assertEquals(4, e2.getTopNTotalCount());

        e1.merge(e2);

        assertEquals(8, e1.getNumRowCounter());
        assertEquals(8, e1.getTopNTotalCount());
        assertEquals(6, e1.getTopNCorrectCount());
        assertEquals(2.0 / 8, e1.accuracy(), 1e-6);
        assertEquals(6.0 / 8, e1.topNAccuracy(), 1e-6);
    }


    @Test
    public void testEvaluationWithMetaData() throws Exception {

        RecordReader csv = new CSVRecordReader();
        csv.initialize(new FileSplit(new ClassPathResource("iris.txt").getTempFileFromArchive()));

        int batchSize = 10;
        int labelIdx = 4;
        int numClasses = 3;

        RecordReaderDataSetIterator rrdsi = new RecordReaderDataSetIterator(csv, batchSize, labelIdx, numClasses);

        NormalizerStandardize ns = new NormalizerStandardize();
        ns.fit(rrdsi);
        rrdsi.setPreProcessor(ns);
        rrdsi.reset();

        Nd4j.getRandom().setSeed(12345);
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().seed(12345).iterations(1)
                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).updater(Updater.SGD)
                        .learningRate(0.1).list()
                        .layer(0, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                        .activation(Activation.SOFTMAX).nIn(4).nOut(3).build())
                        .pretrain(false).backprop(true).build();

        MultiLayerNetwork net = new MultiLayerNetwork(conf);
        net.init();

        for (int i = 0; i < 4; i++) {
            net.fit(rrdsi);
            rrdsi.reset();
        }

        Evaluation e = new Evaluation();
        rrdsi.setCollectMetaData(true); //*** New: Enable collection of metadata (stored in the DataSets) ***

        while (rrdsi.hasNext()) {
            DataSet ds = rrdsi.next();
            List<RecordMetaData> meta = ds.getExampleMetaData(RecordMetaData.class); //*** New - cross dependencies here make types difficult, usid Object internally in DataSet for this***

            INDArray out = net.output(ds.getFeatures());
            e.eval(ds.getLabels(), out, meta); //*** New - evaluate and also store metadata ***
        }

        System.out.println(e.stats());

        System.out.println("\n\n*** Prediction Errors: ***");

        List<Prediction> errors = e.getPredictionErrors(); //*** New - get list of prediction errors from evaluation ***
        List<RecordMetaData> metaForErrors = new ArrayList<>();
        for (Prediction p : errors) {
            metaForErrors.add((RecordMetaData) p.getRecordMetaData());
        }
        DataSet ds = rrdsi.loadFromMetaData(metaForErrors); //*** New - dynamically load a subset of the data, just for prediction errors ***
        INDArray output = net.output(ds.getFeatures());

        int count = 0;
        for (Prediction t : errors) {
            System.out.println(t + "\t\tRaw Data: "
                            + csv.loadFromMetaData((RecordMetaData) t.getRecordMetaData()).getRecord() //*** New - load subset of data from MetaData object (usually batched for efficiency) ***
                            + "\tNormalized: " + ds.getFeatureMatrix().getRow(count) + "\tLabels: "
                            + ds.getLabels().getRow(count) + "\tNetwork predictions: " + output.getRow(count));
            count++;
        }

        int errorCount = errors.size();
        double expAcc = 1.0 - errorCount / 150.0;
        assertEquals(expAcc, e.accuracy(), 1e-5);

        ConfusionMatrix<Integer> confusion = e.getConfusionMatrix();
        int[] actualCounts = new int[3];
        int[] predictedCounts = new int[3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                int entry = confusion.getCount(i, j); //(actual,predicted)
                List<Prediction> list = e.getPredictions(i, j);
                assertEquals(entry, list.size());

                actualCounts[i] += entry;
                predictedCounts[j] += entry;
            }
        }

        for (int i = 0; i < 3; i++) {
            List<Prediction> actualClassI = e.getPredictionsByActualClass(i);
            List<Prediction> predictedClassI = e.getPredictionByPredictedClass(i);
            assertEquals(actualCounts[i], actualClassI.size());
            assertEquals(predictedCounts[i], predictedClassI.size());
        }


    }
}
