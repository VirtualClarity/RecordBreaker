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

import java.io.IOException;
import java.util.Map;
import java.util.SortedMap;
import java.util.Set;
import java.util.List;
import java.util.HashSet;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Iterator;
import java.util.ArrayList;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileStatus;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/*****************************************************************
 * <code>XMLSchemaDescriptor</code> builds an Avro-style schema out of the XML info.
 *
 * @author "Michael Cafarella"
 * @version 1.0
 * @since 1.0
 * @see SchemaDescriptor
 ******************************************************************/
public class XMLSchemaDescriptor extends GenericSchemaDescriptor {
  public static String SCHEMA_ID = "xml";
  TagEnvironment rootTag;
  
  /**
   * Creates a new <code>XMLSchemaDescriptor</code> instance.
   * Processes the input XML data and creates an Avro-compatible
   * Schema representation.
   */
  public XMLSchemaDescriptor(DataDescriptor dd) throws IOException {
    super(dd);
  }

  public XMLSchemaDescriptor(DataDescriptor dd, String schemaRepr, byte[] miscPayload) throws IOException {
    super(dd, schemaRepr);

    // Deserialize the root tag from the payload info
    try {
      this.rootTag = new TagEnvironment(new JSONObject(new String(miscPayload)));
      this.rootTag.setParent(null);
    } catch (JSONException jne) {
      jne.printStackTrace();
      throw new IOException("JSONException: " + jne.toString());
    }
  }

  public byte[] getPayload() {
    JSONObject rootJobj = rootTag.serialize();
    byte results[] = rootJobj.toString().getBytes();
    return results;
  }

  void computeSchema() throws IOException {
    SAXParserFactory factory = SAXParserFactory.newInstance();
    SAXParser parser = null;
    // Unfortunately, validation is often not possible
    factory.setValidating(false);

    try {
      // The XMLProcessor builds up a tree of tags
      XMLProcessor xp = new XMLProcessor();
      parser = factory.newSAXParser();
      parser.parse(dd.getRawBytes(), xp);

      // Grab the root tag
      this.rootTag = xp.getRoot();

      // Once the tree is built, we:
      // a) Find the correct repetition node (and throws out 'bad' repeats)
      // b) Flatten hierarchies of subfields into a single layer, so it's suitable
      //    for relational-style handling
      // c) Build an overall schema object that can summarize every expected
      //    object, even if the objects' individual schemas differ somewhat
      this.rootTag.completeTree();
    } catch (SAXException saxe) {
      throw new IOException(saxe.toString());
    } catch (ParserConfigurationException pcee) {
      throw new IOException(pcee.toString());
    }
  }

  ////////////////////////////////////////////////////////////////////
  // XMLProcessor creates a tree of TagEnvironment objects out of
  // an input XML file.
  // 
  // The result of a parse is a single TagEnvironment root object
  // (which points to the rest of the data).
  ////////////////////////////////////////////////////////////////////  
  class XMLProcessor extends DefaultHandler {
    List<TagEnvironment> environments = new ArrayList<TagEnvironment>();
    List<StringBuffer> tagData = new ArrayList<StringBuffer>();

    public XMLProcessor() {
      environments.add(new TagEnvironment(null, "<root>"));
    }
    public void startElement(String uri, String localName, String qName, Attributes attrs) {
      qName = qName.replace("-","_");
      tagData.add(new StringBuffer());
      TagEnvironment parentEnvironment = environments.get(environments.size()-1);
      TagEnvironment curEnvironment = new TagEnvironment(parentEnvironment, qName);
      parentEnvironment.addChild(curEnvironment);
      environments.add(curEnvironment);
    }
    public void characters(char[] ch, int start, int length) {
      tagData.get(tagData.size()-1).append(new String(ch, start, length));
    }
    public void endElement(String uri, String localName, String qName) {
      qName = qName.replace("-","_");
      String finalTagData = tagData.remove(tagData.size()-1).toString();
      TagEnvironment curEnvironment = environments.remove(environments.size()-1);            
      curEnvironment.setData(finalTagData);
    }
    public TagEnvironment getRoot() {
      return environments.get(0);
    }
  }
  
  ////////////////////////////////////////////////////////////////////
  // TagEnvironment captures a tag and its associated data.
  //
  // There is only one 'repetitionNode' per tree.  This is the node whose
  // children will eventually become records when returned by getIterator().
  // We select the node that has more than one identically-named child AND
  // is closest to the root.
  //
  // Other nodes may have repeated children but which which are further
  // from the root than the repetitionNode.  Maybe someday we will compute
  // a kind of cartesian product among multiple repetitionNodes, but for the moment
  // we simply throw out these extra repeated fields.  We ignore the fields
  // starting with the 2nd appearance.
  //
  ////////////////////////////////////////////////////////////////////
  class TagEnvironment {
    // Several members are appropriate for every node in the tree.
    String label;
    String data;
    boolean repetitionNode;
    TagEnvironment parent;
    List<TagEnvironment> children = new ArrayList<TagEnvironment>();
    
    // These fields are only filled in for TagEnvironment nodes that
    // represent genuine tuples.  They are inappropriate for most
    // nodes in the tree.
    List<Object> typedFields = new ArrayList<Object>();
    List<Schema.Field> fieldSchemas = new ArrayList<Schema.Field>();
    
    /**
     * Deserialize the tagenvironment object from the provided JSON object.
     */
    public TagEnvironment(JSONObject jsonPayload) throws JSONException {
      // label, data, repetition
      this.label = (String) jsonPayload.get("label");
      this.data = (String) jsonPayload.get("data");
      this.repetitionNode = jsonPayload.getBoolean("isRepetitionNode");

      // typedFields and fieldSchemas
      JSONArray jsonFieldSchemas = jsonPayload.getJSONArray("fieldSchemas");
      this.fieldSchemas = new ArrayList<Schema.Field>();
      this.typedFields = new ArrayList<Object>();
      for (int i = 0; i < jsonFieldSchemas.length(); i++) {
        JSONObject jsonFieldSchema = jsonFieldSchemas.getJSONObject(i);
        String name = (String) jsonFieldSchema.get("name");
        Schema s = Schema.parse((String) jsonFieldSchema.get("schema"));
        this.fieldSchemas.add(new Schema.Field(name, s, "", null));

        if (s.getType() == Schema.Type.INT) {
          this.typedFields.add(jsonFieldSchema.getInt("value"));
        } else if (s.getType() == Schema.Type.DOUBLE) {
          this.typedFields.add(jsonFieldSchema.getDouble("value"));          
        } else if (s.getType() == Schema.Type.LONG) {
          this.typedFields.add(jsonFieldSchema.getLong("value"));          
        } else {
          this.typedFields.add((String) jsonFieldSchema.get("value"));
        }
      }

      // children
      JSONArray jsonChildren = jsonPayload.getJSONArray("children");
      this.children = new ArrayList<TagEnvironment>();
      for (int i = 0; i < jsonChildren.length(); i++) {
        TagEnvironment child = new TagEnvironment(jsonChildren.getJSONObject(i));
        child.setParent(this);
        children.add(child);
      }
    }

    /**
     * Simply create a node in the tag tree.
     */
    public TagEnvironment(TagEnvironment parent, String label) {
      this.parent = parent;
      this.label = label;
      this.repetitionNode = false;
    }
    //////////////////////////////////////////////
    // Called by XMLProcessor during XML parsing
    //////////////////////////////////////////////
    public void addChild(TagEnvironment child) {
      this.children.add(child);
    }
    public void setData(String data) {
      this.data = data;
    }

    ////////////////////////////////////////////////
    // Serialize the tag (and its descendents) into a JSON object
    ////////////////////////////////////////////////
    public JSONObject serialize() {
      try {
        // label, data, repetition
        JSONObject jobj = new JSONObject();
        jobj.put("label", label);
        jobj.put("data", (data == null) ? "" : data);
        jobj.put("isRepetitionNode", repetitionNode);

        // typedFields and fieldSchemas
        JSONArray jsonFieldSchemas = new JSONArray();
        for (int i = 0; i < fieldSchemas.size(); i++) {
          Schema.Field sf = fieldSchemas.get(i);
          JSONObject jsonFieldSchema = new JSONObject();
          jsonFieldSchema.put("name", sf.name());
          jsonFieldSchema.put("schema", sf.schema().toString());

          if (sf.schema().getType() == Schema.Type.INT) {
            jsonFieldSchema.put("value", ((Integer) typedFields.get(i)).intValue());
          } else if (sf.schema().getType() == Schema.Type.DOUBLE) {
            jsonFieldSchema.put("value", ((Double) typedFields.get(i)).doubleValue());
          } else if (sf.schema().getType() == Schema.Type.LONG) {
            jsonFieldSchema.put("value", ((Long) typedFields.get(i)).longValue());
          } else {
            jsonFieldSchema.put("value", (String) typedFields.get(i));
          }
          jsonFieldSchemas.put(jsonFieldSchema);
        }
        jobj.put("fieldSchemas", jsonFieldSchemas);

        // children
        JSONArray jsonChildren = new JSONArray();
        jobj.put("children", jsonChildren);
        for (TagEnvironment child: children) {
          jsonChildren.put(child.serialize());
        }
        return jobj;
      } catch (JSONException jne) {
        jne.printStackTrace();
        return null;
      }
    }
    public void setParent(TagEnvironment parent) {
      this.parent = parent;
    }
    
    ////////////////////////////////////////////////
    // Called after the initial XML parse is done.
    // We now figure out various whole-tree properties.
    ////////////////////////////////////////////////
    /**
     * completeTree() figures out the correct level of tuple-repetition.
     * Then it assigns data fields to the correct repeated-node(s).
     * Finally, it computes a single schema that captures all of the fields
     * found across the repeated-node set.
     */
    public void completeTree() {
      this.completeTree(false);
      this.hoistData();
      //addField("XMLExtractedSchema", new Schema.Field("name", Schema.create(Schema.Type.STRING), "", null));
      schema = this.getUnifiedSchema(true);
      Schema.Field sf = schema.getField("name");
    }
    private void completeTree(boolean repetitionNodeFound) {
      Map<String, Integer> nameCounts = new TreeMap<String, Integer>();
      for (TagEnvironment child: children) {
        String l = child.getLabel();
        Integer prevCount = nameCounts.get(l);
        if (prevCount == null) {
          prevCount = new Integer(0);
        }
        nameCounts.put(l, prevCount.intValue() + 1);
      }
      // If at least one child type appears more than once, then it
      // could be the iterator node.
      if (children.size() > nameCounts.size()) {
        if (! repetitionNodeFound) {
          this.repetitionNode = true;
          repetitionNodeFound = true;
        }

        //
        // If this is the iterator node, keep child-repetitions, but throw
        // out the non-repeated-target children.
        //
        // If this is not the iterator node, throw out any child repetitions.
        // Keep only unique attrs
        //
        if (this.repetitionNode) {
          // This is the repetitionNode.  Throw out all children except the
          // repeated one.
          //
          // NOTE: this gets rid of one-off metadata that we might like to retain
          // in the future.
          //
          if (nameCounts.size() > 1) {
            int maxCount = -1;
            String maxKey = null;
            for (Map.Entry<String, Integer> e: nameCounts.entrySet()) {
              if (e.getValue() > maxCount) {
                maxKey = e.getKey();
                maxCount = e.getValue();
              }
            }

            for (Iterator<TagEnvironment> it = children.iterator(); it.hasNext(); ) {
              TagEnvironment child = it.next();
              if (! child.label.equals(maxKey)) {
                it.remove();
              }
            }
          }
        } else {
          // Throw out child repetitions.  This is not the legal repetition-parent-node
          // 
          // NOTE: Eventually, we could use the multiple values to expand a single
          // tuple into multiple rows, but that's too complicated for the moment
          // (and it's unclear if the data warrants it)
          //
          Set<String> seenLabels = new HashSet<String>();
          for (Iterator<TagEnvironment> it = children.iterator(); it.hasNext(); ) {
            TagEnvironment child = it.next();
            Integer count = nameCounts.get(child.label);
            if (seenLabels.contains(child.label) && count.intValue() > 1) {
              it.remove();
            }
            seenLabels.add(child.label);
          }
        }
      }
        
      // If there is more than one child type, then throw out all but
      // the most common child.
      //
      for (TagEnvironment child: children) {
        child.completeTree(repetitionNodeFound);
      }
    }

    /////////////////////////////////////////////////////////////////
    // hoistData() and several helper functions flatten subfield trees
    // and assigns the resulting flattened fields to the repeated-node.
    /////////////////////////////////////////////////////////////////
    void hoistData() {
      if (children.size() == 0) {
        TagEnvironment hoistTarget = getHoistTarget();
        String fieldLabel = buildHoistedLabel(hoistTarget);
        if (fieldLabel.length() == 0) {
          fieldLabel = getLabel();
        }
        hoistTarget.addField(getTypedData(), new Schema.Field(fieldLabel, Schema.create(getDataSchema()), "", null));
      } else {
        for (TagEnvironment child: children) {
          child.hoistData();
        }
      }
    }
    void addField(Object val, Schema.Field sfield) {
      this.typedFields.add(val);
      this.fieldSchemas.add(sfield);
    }
    TagEnvironment getHoistTarget() {
      if (parent.repetitionNode) {
        return this;
      } else {
        return (parent != null) ? parent.getHoistTarget() : parent;
      }
    }
    String buildHoistedLabel(TagEnvironment target) {
      if (this == target) {
        return "";
      } else {
        String result = parent.buildHoistedLabel(target);
        if (result.length() > 0) {
          return result + "_" + label;
        } else {
          return label;
        }
      }
    }
    /**
     * Once the repetition node has been found and the fields assigned,
     * we can compute a Schema that captures fields in all repeated-nodes.
     *
     * This is to handle cases when fields only appear in a subset of nodes.
     * The unified Schema will be the union of all observed fields.
     */
    Schema getUnifiedSchema(boolean initial) {
      if (typedFields.size() > 0) {
        // Build and return schema.
        if (initial) {
          Schema s = Schema.createRecord("name", "", "XMLExtractedSchema", false);
          s.setFields(fieldSchemas);
          return s;
        } else {
          return Schema.createRecord(fieldSchemas);
        }
      } else {
        // Grab all child schemas, and unify.
        Map<String, Schema.Field> observedFields = new TreeMap<String, Schema.Field>();
        for (TagEnvironment child: children) {
          Schema s = child.getUnifiedSchema(false);
          if (s != null) {
            for (Schema.Field childField: s.getFields()) {
              if (observedFields.get(childField.name()) == null) {
                observedFields.put(childField.name(), childField);
              }
            }
          }
        }
        if (observedFields.size() == 0) {
          return null;
        } else {
          List<Schema.Field> singleList = new ArrayList<Schema.Field>();
          for (Map.Entry<String, Schema.Field> cur: observedFields.entrySet()) {
            Schema.Field sf = cur.getValue();
            singleList.add(new Schema.Field(sf.name(), Schema.create(sf.schema().getType()), "", null));
          }
          if (initial) {
            Schema s = Schema.createRecord("XMLSchema", "", "XMLExtractedSchema", false);
            s.setFields(singleList);
            return s;
          } else {
            return Schema.createRecord(singleList);            
          }
        }
      }
    }

    ////////////////////////////////////////////////////////
    // Accessors
    ////////////////////////////////////////////////////////
    public String getLabel() {
      return label;
    }
    /**
     * Used by the Iterator to collect data objects that obey the
     * whole-tree schema.
     */
    public void accumulateObjects(List<Object> accumulator) {
      if (! this.repetitionNode) {
        for (TagEnvironment child: children) {
          child.accumulateObjects(accumulator);
        }
      } else {
        for (TagEnvironment child: children) {
          accumulator.add(child.buildRecord(schema));
        }
      }
    }
    /**
     * Helper function for accumulateObjects()
     */
    Object buildRecord(Schema s) {
      GenericData.Record cur = new GenericData.Record(s);
      for (int i = 0; i < typedFields.size(); i++) {
        Object typedFieldObj = typedFields.get(i);
        String typedFieldLabel = fieldSchemas.get(i).name();
        cur.put(typedFieldLabel, typedFieldObj);
      }
      return cur;
    }

    /**
     * Return the typed version of any data stored at this tag node.
     */
    Object getTypedData() {
      if (data == null) {
        return null;
      }
      try {
        return Integer.parseInt(data);
      } catch (NumberFormatException nfe) {
      }
      try {
        return Double.parseDouble(data);
      } catch (NumberFormatException nfe) {
      }
      try {
        return Long.parseLong(data);
      } catch (NumberFormatException nfe) {
      }
      return data;
    }

    /**
     * Return a schema that describes the type of any data stored at this
     * tag node.
     */
    Schema.Type getDataSchema() {
      if (data == null) {
        return null;
      }
      try {
        Integer.parseInt(data);
        return Schema.Type.INT;
      } catch (NumberFormatException nfe) {
      }
      try {
        Double.parseDouble(data);
        return Schema.Type.DOUBLE;
      } catch (NumberFormatException nfe) {
      }
      try {
        Long.parseLong(data);
        return Schema.Type.LONG;        
      } catch (NumberFormatException nfe) {
      }
      return Schema.Type.STRING;      
    }
    public String toString() {
      return "Tag " + label + " (" + children.size() + " children)";
    }
  }

  /**
   * Return an object that steps through all the data items in the file.
   * It's a bit unclear how this should work, as an XML file is really a tree,
   * but we usually assume an Iterator is giving back tuples.  So what's a "row"
   * in the case of XML?
   *
   * Ideally, the iterator gives back a result for every "repeated leaf".  Just
   * being a leaf in the XML tree is not enough: there should be a level of
   * repetition that makes it interesting.  If there's no repetition then
   * the object is just returned once.
   *
   * We can detect when repeats happen: it yields an Avro array.  But what about
   * when we have multiple levels of this?  Probably we want a row for the
   * product of repetitions.
   *
   * Definitely do not want a record for each individual leaf-level field.
   *
   * Maybe we want to fill out as many non-exclusive columns as we can?
   * As soon as a repetition means we have a column-repetition-conflict, then
   * time for a new row.
   * 
   * How do we translate an Avro tree into a series of tuples?  Basically
   * we have one new Record for each leaf-level array entry.  There is never
   * an array in an object returned by getIterator().  If it's just a nested
   * set of Records, then it's one big tuple.
   */
  public Iterator getIterator() {
    return new Iterator() {
      // NOTE: We should eventually modify this so we don't have to
      // materialize the entire tree.  This is pretty inefficient right now.
      Object nextElt = null;
      List<Object> lookaheadList = new ArrayList<Object>();
      {
        rootTag.accumulateObjects(lookaheadList);
        nextElt = lookahead();
      }
      public boolean hasNext() {
        return nextElt != null;
      }
      public synchronized Object next() {
        Object toReturn = nextElt;
        nextElt = lookahead();
        return toReturn;
      }
      public void remove() {
        throw new UnsupportedOperationException();
      }
      Object lookahead() {
        if (lookaheadList.size() > 0) {
          return lookaheadList.remove(0);
        } else {
          return null;
        }
      }
    };
  }

  /**
   * It's an XML file
   */
  public String getSchemaSourceDescription() {
    return SCHEMA_ID;
  }
}