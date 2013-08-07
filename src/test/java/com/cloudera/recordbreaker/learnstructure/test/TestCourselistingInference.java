/*
 * Copyright (c) 2011, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */
package com.cloudera.recordbreaker.learnstructure.test;

import java.io.File;
import java.io.IOException;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.rules.TemporaryFolder;

/**
 * TestCourselistingInference tests the LearnStructure component for 'commonlog.txt' data
 *
 * @author "Michael Cafarella" <mjc@cloudera.com>
 * @version 1.0
 * @since 1.0
 * @see InferenceTest
 */
public class TestCourselistingInference extends InferenceTest {
  @Rule
  public TemporaryFolder tmpOutDir = new TemporaryFolder();
  File workingDir = null;
  /**
   * Creates a new <code>CourselistingInferenceTest</code> instance.
   *
   */
  public TestCourselistingInference() {
  }

  @Before
  public void prepare() throws IOException {
    workingDir = tmpOutDir.newFolder("workingdir");
  }

  @Test(timeout=10000)
  public void testCourselistingInference() {
    Assert.assertTrue(runSingletonTest(workingDir, new File(sampleDir, "courselisting.txt")));
  }

  @After
  public void teardown() {
    tmpOutDir.delete();
  }
}
