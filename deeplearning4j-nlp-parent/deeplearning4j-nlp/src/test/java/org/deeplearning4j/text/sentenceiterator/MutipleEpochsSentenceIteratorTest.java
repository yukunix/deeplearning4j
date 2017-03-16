package org.deeplearning4j.text.sentenceiterator;

import org.datavec.api.util.ClassPathResource;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * @author raver119@gmail.com
 */
public class MutipleEpochsSentenceIteratorTest {
    @Test
    public void hasNext() throws Exception {
        SentenceIterator iterator = new MutipleEpochsSentenceIterator(
                        new BasicLineIterator(new ClassPathResource("/big/raw_sentences.txt").getFile()), 100);

        int cnt = 0;
        while (iterator.hasNext()) {
            iterator.nextSentence();
            cnt++;
        }

        assertEquals(9716200, cnt);
    }

}
