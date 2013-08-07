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
package com.cloudera.recordbreaker.learnstructure;

import java.io.*;
import java.util.*;
import org.apache.avro.Schema;
import org.apache.avro.io.JsonEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericContainer;
import org.apache.avro.generic.GenericDatumWriter;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.conf.Configuration;

/*********************************************************
 * LearnStructure is the main file for figuring out pattern-extractors and schemas for a text file.
 *
 * This code operates on a raw text file and emits the extractors/schemas.  The user
 * may decide to remove certain extractors/schemas if they only apply to a tiny number of
 * potential lines in the target text file.
 *
 *********************************************************/
public class LearnStructure {
  public static String SCHEMA_FILENAME = "schema.json";
  public static String JSONDATA_FILENAME = "data.avro.json";
  public static String DATA_FILENAME = "data.avro";
  public static String PARSER_FILENAME = "parser.dat";

  public LearnStructure() {
  }
  
  /**
   */
  public void inferRecordFormat(FileSystem fs, Path p, FileSystem fs2, Path schemaFile, Path parseTreeFile, Path jsonDataFile, Path avroDataFile, boolean verbose, int maxLines) throws IOException {
    // Store parse errors and results
    List<Integer> unparseableLineNos = new ArrayList<Integer>();
    List<String> unparseableStrs = new ArrayList<String>();
    List<Integer> parseableLineNos = new ArrayList<Integer>();
    List<List<Token.AbstractToken>> allChunks = new ArrayList<List<Token.AbstractToken>>();

    //
    // Transform the text into a list of "chunks".  A single chunk corresponds to a line of text.  A chunk is a list of Tokens.
    //
    long startRead = System.currentTimeMillis();
    BufferedReader in = new BufferedReader(new InputStreamReader(fs.open(p)));
    try {
      String s = in.readLine();
      int lineno = 0;
      while (s != null) {
        if (maxLines >= 0 && lineno >= maxLines) {
          break;
        }
        List<Token.AbstractToken> chunkToks = Tokenizer.tokenize(s);
        if (chunkToks != null) {
          allChunks.add(chunkToks);
          parseableLineNos.add(lineno);
        } else {
          unparseableStrs.add(s);
          unparseableLineNos.add(lineno);
        }
        s = in.readLine();
        lineno++;
      }
    } finally {
      in.close();
    }

    //
    // Infer type structure from the tokenized chunks
    //
    long start = System.currentTimeMillis();
    InferredType typeTree = TypeInference.infer(allChunks);
    long end = System.currentTimeMillis();
    double loadTime = (start - startRead) / 1000.0;
    double inferTime = (end - start) / 1000.0;
    double totalTime = (end - startRead) / 1000.0;
    if (verbose) {
      System.err.println("Number of chunks: " + allChunks.size());    
      System.err.println("Elapsed load time: " + loadTime);
      System.err.println("Elapsed inference time: " + inferTime);
      System.err.println("Total execution time: " + totalTime);
    }

    //
    // The existing type tree is now correct, but could probably be more succinct.
    // We can now improve/rewrite it.
    //

    //
    // Should every top-level type be ARRAY, so as to allow repeated log lines?
    // Or does the Avro format allow an implict top-level repeating structure?
    //

    //
    // Dump the results.  We emit:
    // 1) A JSON/Avro schema
    // 2) A serialized parser program that can consume data and emit Avro files using the given schema
    //
    Schema s = typeTree.getAvroSchema();
    if (schemaFile != null) {
      BufferedWriter out = new BufferedWriter(new OutputStreamWriter(fs2.create(schemaFile)));
      try {
        out.write(s.toString(true));
      } finally {
        out.close();
      }
    }
    if (parseTreeFile != null) {
      DataOutputStream outd = new DataOutputStream(new BufferedOutputStream(fs2.create(parseTreeFile)));
      try {
        typeTree.write(outd);
      } finally {
        outd.close();
      }
    }

    //
    // Apply the typetree's parser.
    //
    if (jsonDataFile != null) {
      Schema schema = typeTree.getAvroSchema();
      GenericDatumWriter jsonGDWriter = new GenericDatumWriter(schema);      
      BufferedOutputStream outJson = new BufferedOutputStream(fs2.create(jsonDataFile));
      JsonEncoder encoder = EncoderFactory.get().jsonEncoder(schema, outJson);
      try {
        in = new BufferedReader(new InputStreamReader(fs.open(p)));
        try {
          String str = in.readLine();
          while (str != null) {
            GenericContainer gct = typeTree.parse(str);

            if (gct != null) {
              jsonGDWriter.write(gct, encoder);
            }
            str = in.readLine();
          }      
        } finally {
          in.close();
        }
      } finally {
        encoder.flush();
        outJson.close();
      }
    }

    if (avroDataFile != null) {
      int numGoodParses = 0;
      int lineno = 0;
      Schema schema = typeTree.getAvroSchema();

      GenericDatumWriter gdWriter = new GenericDatumWriter(schema);
      DataFileWriter outData = new DataFileWriter(gdWriter);
      outData = outData.create(schema, fs2.create(avroDataFile));

      try {
        in = new BufferedReader(new InputStreamReader(fs.open(p)));
        try {
          String str = in.readLine();
          while (str != null) {
            GenericContainer gct = typeTree.parse(str);
            if (gct != null) {
              numGoodParses++;
              outData.append(gct);
            } else {
              if (verbose) {
                System.err.println("unparsed line: '" + str + "'");
              }
            }
            str = in.readLine();
            lineno++;
          }      
        } finally {
          in.close();
        }
      } finally {
        outData.close();
      }
      if (verbose) {
        System.err.println();
        System.err.println("Total # input lines: " + lineno);
        System.err.println("Total # lines parsed correctly: " + numGoodParses);
      }
    }
  }

  //////////////////////////////////////////
  // main()
  //////////////////////////////////////////
  public static void main(String argv[]) throws IOException {
    if (argv.length < 2) {
      System.err.println("Usage: LearnStructure <input-datafile> <outdir> (-emitAvro (true)|false)");
      return;
    }
    FileSystem localFS = FileSystem.getLocal(new Configuration());
    boolean emitAvro = true;
    int i = 0;
    Path f = new Path(new File(argv[i++]).getCanonicalPath());
    File outdir = new File(argv[i++]).getCanonicalFile();
    for (; i < argv.length; i++) {
      if ("-emitAvro".equals(argv[i])) {
        i++;
        emitAvro = "true".equals(argv[i]);
      }
    }

    System.err.println("Input file: " + f.toString());
    System.err.println("Output directory: " + outdir.getCanonicalPath());
    if (outdir.exists()) {
      throw new IOException("Output directory already exists: " + outdir);
    }
    outdir.mkdirs();
    Path schemaFile = new Path(outdir.getCanonicalPath(), SCHEMA_FILENAME);
    Path parseTreeFile = new Path(outdir.getCanonicalPath(), PARSER_FILENAME);    
    Path jsonDataFile = null;
    Path avroDataFile = null;
    if (emitAvro) {
      jsonDataFile = new Path(outdir.getCanonicalPath(), JSONDATA_FILENAME);    
      avroDataFile = new Path(outdir.getCanonicalPath(), DATA_FILENAME);
    }

    LearnStructure ls = new LearnStructure();
    ls.inferRecordFormat(localFS, f, localFS, schemaFile, parseTreeFile, jsonDataFile, avroDataFile, true, -1);
  }
}
