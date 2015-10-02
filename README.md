RecordBreaker - Automatically learn Avro structures from your data
========================================================================================

Preface
-------
This repository is Virtual Clarity's fork of RecordBreaker. Cloudera seem not to have done anything with RecordBreaker in the last 12 months. We forked the repository to get a successful build for internal use. The commits you will see in this repository are are enough to get the build working on an up-to-date Eclipse/m2e installation, plus some changes/improvements for our use cases. The tests as provided by Cloudera do not currently pass, and are thus skipped in the instructions below. You have been warned - there probably are bugs. 

We have introduced the following changes to RecordBreaker's behaviour in this fork, any of which may break your existing usage.
 * Added special-case CSV detection. If a line of data contains two or more ',' which the tokenizer returns as CHAR tokens rather than STRING tokens, or if only one is found and there are only three tokens in total, then the line is treated as CSV. All comma CHAR tokens are removed from the line and won't be included as tokens when the tokenizer returns (and by extension, will not be fields in the schema).
 * Removed quotes from output. When the tokenizer detects values are surrounded by things like quotes, it now assumes those enclosing characters are not part of the data and does not include them in the schema. Which is nice.
 * Single quoted fields are no longer records. When a single primitive is quoted, it now outputs that as a primitive rather than as a record. Which is also nice.  

Introduction
----------------------------------------------------------------------------------------
RecordBreaker is a project that automatically turns your text-formatted data (server logs, sensor readings, etc) into structured Avro data, without any need to write parsers or extractors.  Its goal is to dramatically reduce the time spent preparing data for analysis, enabling more time for the analysis itself.

You can (and should!) read the full RecordBreaker tutorial here: [http://cloudera.github.com/RecordBreaker/](http://cloudera.github.com/RecordBreaker/)

The RecordBreaker repository is hosted at GitHub, here:
[https://github.com/cloudera/RecordBreaker](https://github.com/cloudera/RecordBreaker)

One interesting part of RecordBreaker is the FishEye system.  It's a
web-based tool for examining and managing the diverse datasets likely
to be found in a typical HDFS installation.  It draws features from
both filesystem management and database administration tools.  Most
interestingly, it uses RecordBreaker techniques to automatically
figure out the structure of files it finds.  You can run it by typing:



    bin/learnstructure fisheye -run <portnum> <localstoragedir>



Where __portnum__ is the HTTP port where FishEye will provide data to the
user, and __localstoreagedir__ is where it will maintain information
about a target filesystem.     


Installation
----------------------------------------------------------------------------------------
$ mvn compile
$ mvn package -Dmaven.test.skip=true -DskipTests


Dependencies
----------------------------------------------------------------------------------------
-- Java JDK 1.6
-- Apache Maven (some version)

