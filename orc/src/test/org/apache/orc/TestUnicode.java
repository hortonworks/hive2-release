/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.orc;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TestUnicode {
    Path workDir = new Path(System.getProperty("test.tmp.dir", "target" + File.separator + "test"
            + File.separator + "tmp"));

    Configuration conf;
    FileSystem fs;
    Path testFilePath;

    private final String type;
    private final int maxLength;
    private final boolean hasRTrim;

    @Parameters
    public static Collection<Object[]> data() {
        ArrayList<Object[]> data = new ArrayList<>();
        for (int j = 0; j < 2; j++) {
            for (int i = 1; i <= 5; i++) {
                data.add(new Object[] { j == 0 ? "char" : "varchar", i, true });
            }
        }
        //data.add(new Object[] {"char", 3});
        return data;
    }

    public TestUnicode(String type, int maxLength, boolean hasRTrim) {
        this.type = type;
        this.maxLength = maxLength;
        this.hasRTrim = hasRTrim;
    }

    static final String[] utf8strs = new String[] {
            // Character.UnicodeBlock GREEK (2 bytes)
            "\u03b1\u03b2\u03b3", "\u03b1\u03b2", "\u03b1\u03b2\u03b3\u03b4",
            "\u03b1\u03b2\u03b3\u03b4",
            // Character.UnicodeBlock MALAYALAM (3 bytes)
            "\u0d06\u0d30\u0d3e", "\u0d0e\u0d28\u0d4d\u0d24\u0d3e", "\u0d13\u0d7c\u0d15\u0d4d",
            // Unicode emoji (4 bytes)
            "\u270f\ufe0f\ud83d\udcdd\u270f\ufe0f", "\ud83c\udf3b\ud83d\udc1d\ud83c\udf6f",
            "\ud83c\udf7a\ud83e\udd43\ud83c\udf77" };

    @Rule
    public TestName testCaseName = new TestName();

    @Before
    public void openFileSystem() throws Exception {
        conf = new Configuration();
        fs = FileSystem.getLocal(conf);
        testFilePath = new Path(workDir, "TestOrcFile." + testCaseName.getMethodName() + ".orc");
        fs.delete(testFilePath, false);
    }

    @Test
    public void testUtf8() throws Exception {
        if (type == "varchar") {
            testVarChar(maxLength);
        } else {
            testChar(maxLength);
        }
    }

    // copied from HiveBaseChar
    public static String enforceMaxLength(String val, int maxLength) {
        if (val == null) {
            return null;
        }
        String value = val;

        if (maxLength > 0) {
            int valLength = val.codePointCount(0, val.length());
            if (valLength > maxLength) {
                // Truncate the excess chars to fit the character length.
                // Also make sure we take supplementary chars into account.
                value = val.substring(0, val.offsetByCodePoints(0, maxLength));
            }
        }
        return value;
    }

    // copied from HiveBaseChar
    public static String getPaddedValue(String val, int maxLength, boolean rtrim) {
        if (val == null) {
            return null;
        }
        if (maxLength < 0) {
            return val;
        }

        int valLength = val.codePointCount(0, val.length());
        if (valLength > maxLength) {
            return enforceMaxLength(val, maxLength);
        }

        if (maxLength > valLength && rtrim == false) {
            // Make sure we pad the right amount of spaces; valLength is in terms of code points,
            // while StringUtils.rpad() is based on the number of java chars.
            int padLength = val.length() + (maxLength - valLength);
            val = StringUtils.rightPad(val, padLength);
        }
        return val;
    }

    public void testChar(int maxLength) throws Exception {
        // char(n)
        TypeDescription schema = TypeDescription.createChar().withMaxLength(maxLength);
        String[] expected = new String[utf8strs.length];
        for (int i = 0; i < utf8strs.length; i++) {
            expected[i] = getPaddedValue(utf8strs[i], maxLength, hasRTrim);
        }
        verifyWrittenStrings(schema, utf8strs, expected);
    }

    public void testVarChar(int maxLength) throws Exception {
        // char(n)
        TypeDescription schema = TypeDescription.createVarchar().withMaxLength(maxLength);
        String[] expected = new String[utf8strs.length];
        for (int i = 0; i < utf8strs.length; i++) {
            expected[i] = enforceMaxLength(utf8strs[i], maxLength);
        }
        verifyWrittenStrings(schema, utf8strs, expected);
    }

    public void verifyWrittenStrings(TypeDescription schema, String[] inputs, String[] expected)
            throws Exception {
        Writer writer =
                OrcFile.createWriter(testFilePath, OrcFile.writerOptions(conf).setSchema(schema)
                        .compress(CompressionKind.NONE).bufferSize(10000));
        VectorizedRowBatch batch = schema.createRowBatch();
        BytesColumnVector col = (BytesColumnVector) batch.cols[0];
        for (int i = 0; i < inputs.length; i++) {
            if (batch.size == batch.getMaxSize()) {
                writer.addRowBatch(batch);
                batch.reset();
            }
            col.setVal(batch.size++, inputs[i].getBytes());
        }
        writer.addRowBatch(batch);
        writer.close();

        Reader reader =
                OrcFile.createReader(testFilePath, OrcFile.readerOptions(conf).filesystem(fs));
        RecordReader rows = reader.rows();
        batch = reader.getSchema().createRowBatch();
        col = (BytesColumnVector) batch.cols[0];
        int idx = 0;
        while (rows.nextBatch(batch)) {
            for (int r = 0; r < batch.size; ++r) {
                assertEquals(String.format("test for %s:%d", schema, maxLength), expected[idx],
                        col.toString(r));
                idx++;
            }
        }
        fs.delete(testFilePath, false);
    }
}