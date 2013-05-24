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
package com.cloudera.recordbreaker.analyzer;

import org.apache.avro.Schema;

import java.util.List;
import java.util.ArrayList;
import java.io.IOException;
import java.io.InputStream;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileStatus;

import org.apache.avro.Schema;

/********************************************************************
 * <code>UnstructuredFileDescriptor</code> holds no structured data.
 * It doesn't have a schema to recover.
 *
 * @author "Michael Cafarella"
 * @version 1.0
 * @since 1.0
 * @see DataDescriptor
 ********************************************************************/
public class UnstructuredFileDescriptor implements DataDescriptor {
  FileSystem fs;
  Path p;
  public UnstructuredFileDescriptor(FileSystem fs, Path p) {
    this.fs = fs;
    this.p = p;
  }

  public Path getFilename() {
    return this.p;
  }

  public String getFileTypeIdentifier() {
    return "unstructured";
  }

  public List<SchemaDescriptor> getSchemaDescriptor() {
    return new ArrayList<SchemaDescriptor>();
  }

  public InputStream getRawBytes() throws IOException {
    return fs.open(p);
  }
  public boolean isHiveSupported() {
    return false;
  }
  public String getDeserializerPayload() {
    throw new UnsupportedOperationException("Cannot run Hive queries on file " + getFilename());
  }
  public Schema getHiveTargetSchema() {
    throw new UnsupportedOperationException("Cannot run Hive queries on file " + getFilename());
  }
  public String getHiveCreateTableStatement(String tablename) {
    throw new UnsupportedOperationException("Cannot run Hive queries on file " + getFilename());
  }
  public String getHiveImportDataStatement(String tablename, Path importFile) {
    throw new UnsupportedOperationException("Cannot run Hive queries on file " + getFilename());    
  }
  public String getStorageFormatString(Schema s) {
    return null;
  }
}