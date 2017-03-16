/*-*
 * Copyright © 2010-2015 Atilika Inc. and contributors (see CONTRIBUTORS.md)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  A copy of the
 * License is distributed with this work in the LICENSE.md file.  You may
 * also obtain a copy of the License from
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.atilika.kuromoji.ipadic;

import com.atilika.kuromoji.TokenizerBase.Mode;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class SearchTokenizerTest {

    private static Tokenizer tokenizer;

    @BeforeClass
    public static void beforeClass() throws Exception {
        tokenizer = new Tokenizer.Builder().mode(Mode.SEARCH).build();
    }

    @Test
    public void testCompoundSplitting() throws IOException {
        assertSegmentation("/search-segmentation-tests.txt");
    }

    public void assertSegmentation(String testFilename) throws IOException {
        LineNumberReader reader = new LineNumberReader(
                        new InputStreamReader(getResourceAsStream(testFilename), StandardCharsets.UTF_8));

        String line;
        while ((line = reader.readLine()) != null) {
            // Remove comments
            line = line.replaceAll("#.*$", "");
            // Skip empty lines or comment lines
            if (line.trim().isEmpty()) {
                continue;
            }

            String[] fields = line.split("\t", 2);
            String text = fields[0];
            List<String> expectedSurfaces = Arrays.asList(fields[1].split("\\s+"));

            assertSegmentation(text, expectedSurfaces);
        }
    }

    public void assertSegmentation(String text, List<String> expectedSurfaces) {
        List<Token> tokens = tokenizer.tokenize(text);

        assertEquals("Input: " + text, expectedSurfaces.size(), tokens.size());

        for (int i = 0; i < tokens.size(); i++) {
            assertEquals(expectedSurfaces.get(i), tokens.get(i).getSurface());
        }
    }

    private InputStream getResourceAsStream(String resource) {
        return this.getClass().getResourceAsStream(resource);
    }
}
