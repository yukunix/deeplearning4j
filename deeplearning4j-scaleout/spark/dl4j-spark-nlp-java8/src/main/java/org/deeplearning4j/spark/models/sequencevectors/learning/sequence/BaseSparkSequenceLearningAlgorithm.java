package org.deeplearning4j.spark.models.sequencevectors.learning.sequence;

import org.deeplearning4j.models.embeddings.WeightLookupTable;
import org.deeplearning4j.models.embeddings.learning.ElementsLearningAlgorithm;
import org.deeplearning4j.models.embeddings.loader.VectorsConfiguration;
import org.deeplearning4j.models.sequencevectors.interfaces.SequenceIterator;
import org.deeplearning4j.models.sequencevectors.sequence.Sequence;
import org.deeplearning4j.models.sequencevectors.sequence.ShallowSequenceElement;
import org.deeplearning4j.models.word2vec.wordstore.VocabCache;
import org.deeplearning4j.spark.models.sequencevectors.learning.SparkSequenceLearningAlgorithm;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.concurrent.atomic.AtomicLong;

/**
 * @author raver119@gmail.com
 */
public abstract class BaseSparkSequenceLearningAlgorithm implements SparkSequenceLearningAlgorithm {
    protected transient VocabCache<ShallowSequenceElement> vocabCache;
    protected transient VectorsConfiguration vectorsConfiguration;
    protected transient ElementsLearningAlgorithm<ShallowSequenceElement> elementsLearningAlgorithm;

    @Override
    public void configure(VocabCache<ShallowSequenceElement> vocabCache,
                    WeightLookupTable<ShallowSequenceElement> lookupTable, VectorsConfiguration configuration) {
        this.vocabCache = vocabCache;
        this.vectorsConfiguration = configuration;
    }

    @Override
    public void pretrain(SequenceIterator<ShallowSequenceElement> iterator) {
        // no-op by default
    }

    @Override
    public boolean isEarlyTerminationHit() {
        return false;
    }

    @Override
    public INDArray inferSequence(Sequence<ShallowSequenceElement> sequence, long nextRandom, double learningRate,
                    double minLearningRate, int iterations) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ElementsLearningAlgorithm<ShallowSequenceElement> getElementsLearningAlgorithm() {
        return elementsLearningAlgorithm;
    }

    @Override
    public void finish() {
        // no-op on spark
    }
}
