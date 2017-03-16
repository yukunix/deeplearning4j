package org.deeplearning4j.nn.conf.preprocessor.custom;

import lombok.EqualsAndHashCode;
import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.nn.api.MaskState;
import org.deeplearning4j.nn.conf.InputPreProcessor;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.nd4j.linalg.api.ndarray.INDArray;

/**
 * Created by Alex on 09/09/2016.
 */
@EqualsAndHashCode
public class MyCustomPreprocessor implements InputPreProcessor {

    @Override
    public INDArray preProcess(INDArray input, int miniBatchSize) {
        return input.add(1.0);
    }

    @Override
    public INDArray backprop(INDArray output, int miniBatchSize) {
        return output;
    }

    @Override
    public InputPreProcessor clone() {
        return new MyCustomPreprocessor();
    }

    @Override
    public InputType getOutputType(InputType inputType) {
        return inputType;
    }

    @Override
    public Pair<INDArray, MaskState> feedForwardMaskArray(INDArray maskArray, MaskState currentMaskState,
                    int minibatchSize) {
        throw new UnsupportedOperationException();
    }
}
