package org.deeplearning4j.nn.layers.custom;

import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.layers.custom.testclasses.CustomActivation;
import org.junit.Test;
import org.nd4j.linalg.activations.IActivation;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.nd4j.shade.jackson.databind.ObjectMapper;
import org.nd4j.shade.jackson.databind.introspect.AnnotatedClass;
import org.nd4j.shade.jackson.databind.jsontype.NamedType;

import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by Alex on 19/12/2016.
 */
public class TestCustomActivation {

    @Test
    public void testCustomActivationFn() {

        //First: Ensure that the CustomActivation class is registered
        ObjectMapper mapper = NeuralNetConfiguration.mapper();

        AnnotatedClass ac = AnnotatedClass.construct(IActivation.class,
                        mapper.getSerializationConfig().getAnnotationIntrospector(), null);
        Collection<NamedType> types = mapper.getSubtypeResolver().collectAndResolveSubtypes(ac,
                        mapper.getSerializationConfig(), mapper.getSerializationConfig().getAnnotationIntrospector());
        boolean found = false;
        for (NamedType nt : types) {
            System.out.println(nt);
            if (nt.getType() == CustomActivation.class)
                found = true;
        }

        assertTrue("CustomActivation: not registered with NeuralNetConfiguration mapper", found);

        //Second: let's create a MultiLayerCofiguration with one, and check JSON and YAML config actually works...

        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().learningRate(0.1).list()
                        .layer(0, new DenseLayer.Builder().nIn(10).nOut(10).activation(new CustomActivation()).build())
                        .layer(1, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT).nIn(10).nOut(10).build())
                        .pretrain(false).backprop(true).build();

        String json = conf.toJson();
        String yaml = conf.toYaml();

        System.out.println(json);

        MultiLayerConfiguration confFromJson = MultiLayerConfiguration.fromJson(json);
        assertEquals(conf, confFromJson);

        MultiLayerConfiguration confFromYaml = MultiLayerConfiguration.fromYaml(yaml);
        assertEquals(conf, confFromYaml);

    }

}
