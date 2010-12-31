// (c) Copyright (2010) Cloudera, Inc.
package com.cloudera.learnavro;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/*********************************************************
 * Tokenizer transforms a line of text into a set of Token objects.
 * Each Token is one of a handful of classes.
 *
 *********************************************************/
public class Tokenizer {
  // The components of possible date patterns
  static String monthPatternStrs[] = {"(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)", "([01]*\\d)"};
  static String dateSeparatorPatternStrs[] = {"(?:\\s+)", "(?:\\.)", "(?:\\/)"};
  static String dateDayPatternStr = "([0123]?\\d)";
  static String dateYearPatternStr = "([12]\\d{3})";

  static List<Pattern> monthFirstPatterns = new ArrayList<Pattern>();
  static List<Pattern> yearFirstPatterns = new ArrayList<Pattern>();
  static List<Pattern> dayFirstPatterns = new ArrayList<Pattern>();

  /**
  static Pattern datePattern1 = Pattern.compile("(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+([123]*\\d)\\s+(\\d{4})");
  static Pattern datePattern2 = Pattern.compile("(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+([123]*\\d)");
  **/
  static Pattern ipAddrPattern = Pattern.compile("((?:(?:\\d+\\.){3,}\\d+)|(?:\\*\\.(?:(?:\\d+|\\*)\\.)*(?:\\d+|\\*)))");
  static Pattern permissionBitPattern = Pattern.compile("([drwx-]{9,})");
  static Pattern timePattern1 = Pattern.compile("(\\d\\d):(\\d\\d):(\\d\\d)");
  static Pattern timePattern2 = Pattern.compile("(\\d\\d):(\\d\\d)");
  static Pattern intPattern = Pattern.compile("([-+]?\\d+)");
  static Pattern intRangePattern = Pattern.compile("(\\d+)-(\\d+)");
  static Pattern floatPattern = Pattern.compile("([+-]?\\d*\\.\\d+)");
  static Pattern floatRangePattern = Pattern.compile("(\\d*\\.\\d+)-(\\d*\\.\\d+)");
  static Pattern stringPattern = Pattern.compile("(\\p{Alnum}{2,})");
  static Pattern charPattern = Pattern.compile("(\\S)");
  static Pattern eolPattern = Pattern.compile("(\\n)");
  static Pattern wsPattern = Pattern.compile("(\\s+)");
  static HashMap<String, String> complements;
  static HashMap<String, String> reverseComplements;

  static {
    complements = new HashMap<String, String>();
    complements.put("[", "]");
    complements.put("{", "}");
    complements.put("\"", "\"");
    complements.put("'", "'");
    complements.put("<", ">");
    complements.put("(", ")");
    reverseComplements = new HashMap<String, String>();
    reverseComplements.put("]", "[");
    reverseComplements.put("}", "{");
    reverseComplements.put("\"", "\"");
    reverseComplements.put("'", "'");
    reverseComplements.put(">", "<");
    reverseComplements.put(")", "(");

    // Construct the date patterns
    for (String separatorPatternStr: dateSeparatorPatternStrs) {
      for (String monthPatternStr: monthPatternStrs) {
        // Create all legal combos of month, day, year, and separator
        monthFirstPatterns.add(Pattern.compile(monthPatternStr + separatorPatternStr + dateDayPatternStr + separatorPatternStr + dateYearPatternStr));
        yearFirstPatterns.add(Pattern.compile(dateYearPatternStr + separatorPatternStr + monthPatternStr + separatorPatternStr + dateDayPatternStr));
        dayFirstPatterns.add(Pattern.compile(dateDayPatternStr + separatorPatternStr + monthPatternStr + separatorPatternStr + dateYearPatternStr));
      }
    }
    for (String separatorPatternStr: dateSeparatorPatternStrs) {
      monthFirstPatterns.add(Pattern.compile(monthPatternStrs[0] + separatorPatternStr + dateDayPatternStr));
      dayFirstPatterns.add(Pattern.compile(dateDayPatternStr + separatorPatternStr + monthPatternStrs[0]));
    }
  }

  private static String cutChunk(Matcher m, String curS) {
    int lastGroupChar = m.end(m.groupCount());
    if (curS.length() > lastGroupChar) {
      return curS.substring(lastGroupChar);
    } else {
      return "";
    }
  }

  /**
   * Accepts a single line of input, returns all the tokens for that line.
   * If the line cannot be parsed, we return null.
   */
  static public List<Token.AbstractToken> tokenize(String s) throws IOException {
    String curS = s;
    List<Token.AbstractToken> toksSoFar = new ArrayList<Token.AbstractToken>();

    // We now repeatedly pass through a series of text-extractor tests.
    //System.err.println("PARSE: " + s);
    while (curS.length() > 0) {
      //System.err.println("CurS: '" + curS + "', tokSetSize: " + toksSoFar.size());
      int newStart = -1;

      // META
      char startChar = curS.charAt(0);
      if (complements.get("" + startChar) != null) {
        //System.err.println("START CHAR: " + startChar);
        String closeChar = complements.get("" + startChar);
        int closeIndex = curS.indexOf(closeChar, 1);
        if (closeIndex >= 0) {
          toksSoFar.add(new Token.MetaToken(new Token.CharToken(curS.charAt(0)), new Token.CharToken(closeChar.charAt(0)), tokenize(curS.substring(1, closeIndex))));
          curS = curS.substring(closeIndex+1);
          continue;
        }
      }

      // IP ADDR
      Matcher m = ipAddrPattern.matcher(curS);
      if (m.lookingAt()) {
        toksSoFar.add(new Token.IPAddrToken(m.group(1)));
        curS = cutChunk(m, curS);
        continue;
      }

      // PERMISSION BITS
      m = permissionBitPattern.matcher(curS);
      if (m.lookingAt()) {
        toksSoFar.add(new Token.PermissionBits(m.group(1)));
        curS = cutChunk(m, curS);
        continue;
      }

      // DATE
      // Because of the huge number of possible date patterns, and our desire to not perform multi-token parsing,
      // the date-processing here is a bit of a mess.
      boolean shouldContinue = false;
      for (Pattern p: monthFirstPatterns) {
        m = p.matcher(curS);
        if (m.lookingAt()) {
          if (m.groupCount() == 2) {
            try {
              toksSoFar.add(new Token.DateToken(m.group(2), m.group(1)));
            } catch (IOException iex) {
              continue;
            }
          } else {
            try {
              toksSoFar.add(new Token.DateToken(m.group(2), m.group(1), m.group(3)));
            } catch (IOException iex) {
              continue;
            }
          }
          curS = cutChunk(m, curS);
          shouldContinue = true;
          break;
        }
      }
      if (shouldContinue) {
        continue;
      }

      for (Pattern p: yearFirstPatterns) {
        m = p.matcher(curS);
        if (m.lookingAt()) {
          try {
            toksSoFar.add(new Token.DateToken(m.group(3), m.group(2), m.group(1)));
          } catch (IOException iex) {
            continue;
          }
          curS = cutChunk(m, curS);
          shouldContinue = true;
          break;
        }
      }
      if (shouldContinue) {
        continue;
      }

      for (Pattern p: dayFirstPatterns) {
        m = p.matcher(curS);
        if (m.lookingAt()) {
          if (m.groupCount() == 2) {
            try {
              toksSoFar.add(new Token.DateToken(m.group(1), m.group(2)));
            } catch (IOException iex) {
              continue;
            }
          } else {
            try {
              toksSoFar.add(new Token.DateToken(m.group(1), m.group(2), m.group(3)));
            } catch (IOException iex) {
              continue;
            }
          }
          curS = cutChunk(m, curS);
          shouldContinue = true;
          break;
        }
      }
      if (shouldContinue) {
        continue;
      }

      /**
      m = datePattern1.matcher(curS);
      if (m.lookingAt()) {
        System.err.println("Got " + m.group(1) + " on " + curS);
        toksSoFar.add(new Token.DateToken(m.group(1), m.group(2), m.group(3)));
        curS = cutChunk(m, curS);
        continue;
      }
      m = datePattern2.matcher(curS);
      if (m.lookingAt()) {
        toksSoFar.add(new Token.DateToken(m.group(1), m.group(2)));
        curS = cutChunk(m, curS);
        continue;
      }
      **/

      // TIME
      m = timePattern1.matcher(curS);
      if (m.lookingAt()) {
        toksSoFar.add(new Token.TimeToken(m.group(1), m.group(2), m.group(3)));
        curS = cutChunk(m, curS);
        continue;
      }
      m = timePattern2.matcher(curS);
      if (m.lookingAt()) {
        toksSoFar.add(new Token.TimeToken(m.group(1), m.group(2), "00"));
        curS = cutChunk(m, curS);
        continue;
      }

      // FLOAT RANGE
      m = floatRangePattern.matcher(curS);
      if (m.lookingAt()) {
        toksSoFar.add(new Token.FloatToken(m.group(1)));
        toksSoFar.add(new Token.CharToken('-'));
        toksSoFar.add(new Token.FloatToken(m.group(2)));
        curS = cutChunk(m, curS);
        continue;
      }

      // INTEGER RANGE
      // REMIND - mjc - Should there be a dedicated Token class for ranges?
      m = intRangePattern.matcher(curS);
      if (m.lookingAt()) {
        toksSoFar.add(new Token.IntToken(m.group(1)));
        toksSoFar.add(new Token.CharToken('-'));
        toksSoFar.add(new Token.IntToken(m.group(2)));
        curS = cutChunk(m, curS);
        continue;
      }

      // FLOAT
      m = floatPattern.matcher(curS);
      if (m.lookingAt()) {
        toksSoFar.add(new Token.FloatToken(m.group(1)));
        curS = cutChunk(m, curS);
        continue;
      }

      // INTEGER
      m = intPattern.matcher(curS);
      if (m.lookingAt()) {
        toksSoFar.add(new Token.IntToken(m.group(1)));
        curS = cutChunk(m, curS);
        continue;
      }

      // STRING
      m = stringPattern.matcher(curS);
      if (m.lookingAt()) {
        toksSoFar.add(new Token.StringToken(m.group(1)));
        curS = cutChunk(m, curS);
        continue;
      }

      // CHAR
      m = charPattern.matcher(curS);
      if (m.lookingAt()) {
        toksSoFar.add(new Token.CharToken(m.group(1).charAt(0)));
        curS = cutChunk(m, curS);
        continue;
      }

      // EOL-Token
      m = eolPattern.matcher(curS);
      if (m.lookingAt()) {
        toksSoFar.add(new Token.EOLToken());
        curS = cutChunk(m, curS);
        continue;
      }

      // Whitespace
      m = wsPattern.matcher(curS);
      if (m.lookingAt()) {
        toksSoFar.add(new Token.WhitespaceToken());
        curS = cutChunk(m, curS);
        continue;
      }

      // DEFAULT
      // If execution reaches this point, it means no pattern applied, which means the line cannot be parsed.
      return null;
    }
    return toksSoFar;
  }

  ///////////////////////////////////////////////////
  // main() tests the Tokenizer.
  ////////////////////////////////////////////////////
  public static void main(String argv[]) throws IOException {
    if (argv.length < 1) {
      System.err.println("Usage: Tokenizer <datafile> (-verbose)");
      return;
    }
    File f = new File(argv[0]).getCanonicalFile();
    boolean verbose = false;
    for (int i = 1; i < argv.length; i++) {
      if ("-verbose".equals(argv[i])) {
        verbose = true;
      }
    }
    System.err.println("Input file: " + f.getCanonicalPath());

    // Store parse errors and results
    List<Integer> unparseableLineNos = new ArrayList<Integer>();
    List<String> unparseableStrs = new ArrayList<String>();
    List<Integer> parseableLineNos = new ArrayList<Integer>();
    List<List<Token.AbstractToken>> allChunks = new ArrayList<List<Token.AbstractToken>>();

    // Transform the text into a list of "chunks".  
    // A single chunk corresponds to a line of text.  A chunk is a list of Tokens.
    int totalCount = 0;
    int parsedCount = 0;
    int errorCount = 0;
    BufferedReader in = new BufferedReader(new FileReader(f));
    try {
      String s = in.readLine();
      int lineno = 0;
      while (s != null) {
        List<Token.AbstractToken> chunkToks = Tokenizer.tokenize(s);
        if (chunkToks != null) {
          allChunks.add(chunkToks);
          parseableLineNos.add(lineno);
          parsedCount++;
        } else {
          unparseableStrs.add(s);
          unparseableLineNos.add(lineno);
          errorCount++;
        }
        s = in.readLine();
        lineno++;
        totalCount++;
      }
    } finally {
      in.close();
    }

    System.err.println();
    System.err.println("Total lines: " + totalCount);
    System.err.println("Parsed lines: " + parsedCount + " (" + (1.0*parsedCount / totalCount) + ")");
    System.err.println("Error lines: " + errorCount + " (" + (1.0*errorCount / totalCount) + ")");

    //
    // Print out parsed tokens
    //
    if (verbose) {
      System.err.println();
      System.err.println("--RESULTS--------");
      int i = 0;
      for (List<Token.AbstractToken> chunk: allChunks) {
        System.err.print(parseableLineNos.get(i) + ".  ");
        for (Token.AbstractToken tok: chunk) {
          System.err.print(tok + "  ");
        }
        System.err.println();
        i++;
      }

      //
      // Print out error strings
      //
      System.err.println();
      System.err.println("--ERRORS---------");
      i = 0;
      for (String s: unparseableStrs) {
        System.err.println(unparseableLineNos.get(i) + ".  " + s);
        i++;
      }
    }
  }
}
