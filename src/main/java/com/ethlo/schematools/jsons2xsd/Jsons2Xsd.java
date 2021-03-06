package com.ethlo.schematools.jsons2xsd;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

public class Jsons2Xsd {
    private final static ObjectMapper mapper = new ObjectMapper();

    private static String ns;

    public enum OuterWrapping {
        ELEMENT, TYPE
    }

    private static final Map<String, String> typeMapping = new HashMap<>();

    static {
        // Primitive types
        typeMapping.put("string", "string");
        typeMapping.put("object", "object");
        typeMapping.put("array", "array");
        typeMapping.put("number", "decimal");
        typeMapping.put("boolean", "boolean");
        typeMapping.put("integer", "int");

        // Non-standard, often encountered in the wild
        typeMapping.put("int", "int");
        typeMapping.put("date-time", "dateTime");
        typeMapping.put("time", "time");
        typeMapping.put("date", "date");

        // TODO: Support "JSON null"

        // String formats
        typeMapping.put("string|uri", "anyURI");
        typeMapping.put("string|email", "string");
        typeMapping.put("string|phone", "string");
        typeMapping.put("string|date-time", "dateTime");
        typeMapping.put("string|date", "date");
        typeMapping.put("string|time", "time");
        typeMapping.put("string|utc-millisec", "long");
        typeMapping.put("string|regex", "string");
        typeMapping.put("string|color", "string");
        typeMapping.put("string|style", "string");
    }

    public static JsonNode upgradeToDbsSchema(JsonNode rootNode){
    	JsonNode returnVal=rootNode;
    	if(rootNode.has("definitions")){
    		ObjectNode object = (ObjectNode) rootNode;
    		object.set("properties", rootNode.get("definitions"));
    		object.remove("definitions");
    		object.set("type", new TextNode("object"));
    		returnVal= object;
    	}
    	try{
    	 ObjectMapper arrayMapper = new ObjectMapper();
         String unfilteredContent=returnVal.toString();
       
         final String regex = "(([^}\\s]*)\"additionalProperties\":(?:[^\"]*[false]))";
         String filteredContent=unfilteredContent.replaceAll(regex, "");  
         
         final String regex1 = "(([^}\\s]*)\"additionalItems\":(?:[^\"]*[false]))";
         String finalfilteredContent=filteredContent.replaceAll(regex, "");  
        
         returnVal = arrayMapper.readTree(finalfilteredContent);
    	}catch(Exception e){
    		e.printStackTrace();
    	}
    	return returnVal;
    }
    
    public static Document convert(Reader jsonSchema, String targetNameSpaceUri, OuterWrapping wrapping, String name) throws IOException {
        JsonNode rootNode = mapper.readTree(jsonSchema);
        
        rootNode=upgradeToDbsSchema(rootNode);
        name=handleCamelCasing(name);
        
        final Document xsdDoc = XmlUtil.newDocument();
        xsdDoc.setXmlStandalone(true);

        final Element schemaRoot = createXsdElement(xsdDoc, "schema");
        schemaRoot.setAttribute("targetNamespace", targetNameSpaceUri);
        schemaRoot.setAttribute("xmlns:" + name.toLowerCase(), targetNameSpaceUri);
        ns = name.toLowerCase();


        schemaRoot.setAttribute("elementFormDefault", "qualified");
//		schemaRoot.setAttribute("attributeFormDefault", "qualified");


        final String type = rootNode.path("type").textValue();
        Assert.isTrue("object".equals(type), "root should have type=\"object\"");

        final JsonNode properties = rootNode.get("properties");
        Assert.notNull(properties, "\"properties\" property should be found in root of JSON schema\"");


        Element wrapper = schemaRoot;
        if (wrapping == OuterWrapping.ELEMENT) {
            wrapper = createXsdElement(schemaRoot, "element");
            wrapper.setAttribute("name", name);
            wrapper.setAttribute("type", name.toLowerCase() + ":" + name);

        }

        final Element schemaComplexType = createXsdElement(schemaRoot, "complexType");

        //if (wrapping == OuterWrapping.TYPE)
        {
            schemaComplexType.setAttribute("name", name);
        }
        final Element schemaSequence = createXsdElement(schemaComplexType, "sequence");

        doIterate(schemaSequence, properties, getRequiredList(rootNode));

        //handle external defs
        final JsonNode definitions = rootNode.path("definitions");
        Assert.notNull(definitions, "\"definitions\"  should be found in root of JSON schema\"");

        doIterateDefinitions(schemaRoot, definitions);


        return xsdDoc;
    }

    private static void doIterateDefinitions(Element elem, JsonNode node) {
        final Iterator<Entry<String, JsonNode>> fieldIter = node.fields();
        while (fieldIter.hasNext()) {



            //Create a complex type
            //get properties
            //call doiteration with properties

            final Entry<String, JsonNode> entry = fieldIter.next();
            final String key = entry.getKey();
            final JsonNode val = entry.getValue();
            if (key.equals("Link")) {
                final Element schemaComplexType = createXsdElement(elem, "complexType");
                schemaComplexType.setAttribute("name", key);
                final Element href = createXsdElement(schemaComplexType, "attribute");
                final Element rel = createXsdElement(schemaComplexType, "attribute");
                final Element title = createXsdElement(schemaComplexType, "attribute");
                final Element method = createXsdElement(schemaComplexType, "attribute");
                final Element type = createXsdElement(schemaComplexType, "attribute");

                href.setAttribute("name", "href");
                href.setAttribute("type", "string");

                rel.setAttribute("name", "rel");
                rel.setAttribute("type", "string");

                title.setAttribute("name", "title");
                title.setAttribute("type", "string");

                method.setAttribute("name", "method");
                method.setAttribute("type", "string");

                type.setAttribute("name", "type");
                type.setAttribute("type", "string");


            }
            else {


                final Element schemaComplexType = createXsdElement(elem, "complexType");
                schemaComplexType.setAttribute("name", key);

                final Element schemaSequence = createXsdElement(schemaComplexType, "sequence");
                final JsonNode properties = val.get("properties");
                Assert.notNull(properties, "\"properties\" property should be found in \"" + key + "\"");

                doIterate(schemaSequence, properties, getRequiredList(val));
            }

        }
    }

    private static void doIterate(Element elem, JsonNode node, List<String> requiredList) {
        final Iterator<Entry<String, JsonNode>> fieldIter = node.fields();
        while (fieldIter.hasNext()) {
            final Entry<String, JsonNode> entry = fieldIter.next();
            final String key = entry.getKey();
            final JsonNode val = entry.getValue();
            doIterateSingle(key, val, elem, requiredList.contains(key));
        }
    }

    private static String handleCamelCasing(String name){
    	if(!Character.isUpperCase(name.charAt(1)))
    		return name.substring(0, 1).toLowerCase() + name.substring(1);
    	else
    		return name;
    	
    }
    private static void doIterateSingle(String key, JsonNode val, Element elem, boolean required) {
    	
        final String xsdType = determineXsdType(key, val);
        final Element nodeElem = createXsdElement(elem, "element");
        String name;
        if (!key.equals("link")) {
            name = key.substring(0, 1).toUpperCase() + key.substring(1);
        } else {
            name = key;
        }
        
        
        name=handleCamelCasing(name);
        
        
        nodeElem.setAttribute("name", name);

        if (!"object".equals(xsdType) && !"array".equals(xsdType)) {
            // Simple type
            nodeElem.setAttribute("type", xsdType);
        }
        if (!required) {
            // Not required
            nodeElem.setAttribute("minOccurs", "0");
        }

        switch (xsdType) {
            case "array":
                handleArray(nodeElem, val);
                break;

            case "decimal":
            case "int":
                handleNumber(nodeElem, xsdType, val);
                break;

            case "enum":
                handleEnum(nodeElem, val);
                break;

            case "object":
            	//System.out.println("-----------------"+key);
                handleObject(nodeElem, val);
                break;

            case "string":
                handleString(nodeElem, val);
                break;

            case "reference":
                handleReference(nodeElem, val);
                break;
        }


    }


    private static void handleReference(Element nodeElem, JsonNode val) {
        final JsonNode refs = val.get("$ref");
        nodeElem.removeAttribute("type");
        String fixRef = refs.asText().replace("#/definitions/", ns + ":");
        String name = fixRef.substring(ns.length() + 1);
        String oldName = nodeElem.getAttribute("name");


        if (oldName.length() <= 0) {
            nodeElem.setAttribute("name", name);
        }
        nodeElem.setAttribute("type", fixRef);


    }

    private static void handleString(Element nodeElem, JsonNode val) {
        final Integer minimumLength = getIntVal(val, "minLength");
        final Integer maximumLength = getIntVal(val, "maxLength");
        final String expression = val.path("pattern").textValue();


        if (minimumLength != null || maximumLength != null || expression != null) {
            nodeElem.removeAttribute("type");
            final Element simpleType = createXsdElement(nodeElem, "simpleType");
            final Element restriction = createXsdElement(simpleType, "restriction");
            restriction.setAttribute("base", "string");

            if (minimumLength != null) {
                final Element min = createXsdElement(restriction, "minLength");
                min.setAttribute("value", Integer.toString(minimumLength));
            }

            if (maximumLength != null) {
                final Element max = createXsdElement(restriction, "maxLength");
                max.setAttribute("value", Integer.toString(maximumLength));
            }

            if (expression != null) {
                final Element max = createXsdElement(restriction, "pattern");
                max.setAttribute("value", expression);
            }
        }
    }

    private static void handleObject(Element nodeElem, JsonNode val) {
        final JsonNode properties = val.get("properties");
        if (properties != null && !val.has("oneOf")) {
            System.out.println("In PROP");
            final Element complexType = createXsdElement(nodeElem, "complexType");
            final Element sequence = createXsdElement(complexType, "sequence");
            
            Assert.notNull(properties, "'object' type must have a 'properties' attribute");
            doIterate(sequence, properties, getRequiredList(val));
        }
        if(val.has("oneOf")){
        	System.out.println("In PROP oneOf");
            final Element complexType = createXsdElement(nodeElem, "complexType");
            final Element sequence = createXsdElement(complexType, "sequence");
            final Element choice = createXsdElement(sequence, "choice");
           
        
            try{
            ObjectMapper arrayMapper = new ObjectMapper();
            String unfilteredContent=val.toString();
          
            final String regex = "(([^}\\s]*)\"additionalProperties\":(?:[^\"]*[false]))";
            String filteredContent=unfilteredContent.replaceAll(regex, "");  
           
            JsonNode arrNode = arrayMapper.readTree(filteredContent);
            arrNode = arrNode.get("oneOf");
            List<String> allReqParams=new ArrayList<String>();
            
            StringBuilder newPackedJson=new StringBuilder("{");
            if (arrNode.isArray()) {
            	System.out.println("********************** Manual changes required **********************");
            	int count=1;
                for (final JsonNode objNode : arrNode) {
                    //System.out.println(objNode);
                	String suggestedNodeName="dummyElement"+count;
	                if(objNode.has("description"))
	                    suggestedNodeName=objNode.get("description").toString().replaceAll("\\s","");
	                    	  
	                allReqParams.add(suggestedNodeName);
	                System.out.println("Providing dummy element name : "+suggestedNodeName+" name inside 'oneOf'.");
                    if(arrNode.size()!=count)
                    	newPackedJson.append(suggestedNodeName+":").append(objNode.toString()).append(",");
                    else
                    	newPackedJson.append(suggestedNodeName+":").append(objNode.toString());
                    
                	count++;
                }
                newPackedJson.append("}");
                System.out.println("***********************************End**********************************");
            }
            
            ObjectMapper choiceMapper = new ObjectMapper();
            JsonNode newPackedJsonObj = choiceMapper.readTree(newPackedJson.toString());
            doIterate(choice, newPackedJsonObj, allReqParams);
            }catch(Exception e){
            	System.out.println("Malformed 'oneOf' clause . Please verify :"+val.toString());
            	//e.printStackTrace();
            }
            /*String choicesString=choices.toString();
            choicesString=choicesString.substring(2,choicesString.length()-1);
            choicesString="{\"properties\":{"+choicesString+"}";	
            final String regex = "(([^}\\s]*)\"additionalProperties\":(?:[^\"]*[false]))";
            choicesString=choicesString.replaceAll(regex, "");  
            ObjectMapper mapper = new ObjectMapper();
            try{
            JsonNode newChoiceObj = mapper.readTree(choicesString);
            JsonNode props=newChoiceObj.get("properties");
            List<String> allReqParams=new ArrayList<String>();
            Iterator<String> keys=props.fieldNames();
            while(keys.hasNext()){
            	allReqParams.add(keys.next().toString());
            }
            
            doIterate(choice, props, allReqParams);
            }catch(Exception e){
            	e.printStackTrace();
            }*/
        }

    }

    private static void handleEnum(Element nodeElem, JsonNode val) {
        nodeElem.removeAttribute("type");
        final Element simpleType = createXsdElement(nodeElem, "simpleType");
        final Element restriction = createXsdElement(simpleType, "restriction");
        restriction.setAttribute("base", "string");
        final JsonNode enumNode = val.get("enum");
        for (int i = 0; i < enumNode.size(); i++) {
            final String enumVal = enumNode.path(i).asText();
            final Element enumElem = createXsdElement(restriction, "enumeration");
            enumElem.setAttribute("value", enumVal);
        }
    }

    private static void handleNumber(Element nodeElem, String xsdType, JsonNode jsonNode) {
        final Integer minimum = getIntVal(jsonNode, "minimum");
        final Integer maximum = getIntVal(jsonNode, "maximum");

        if (minimum != null || maximum != null) {
            nodeElem.removeAttribute("type");
            final Element simpleType = createXsdElement(nodeElem, "simpleType");
            final Element restriction = createXsdElement(simpleType, "restriction");
            restriction.setAttribute("base", xsdType);

            if (minimum != null) {
                final Element min = createXsdElement(restriction, "minInclusive");
                min.setAttribute("value", Integer.toString(minimum));
            }

            if (maximum != null) {
                final Element max = createXsdElement(restriction, "maxInclusive");
                max.setAttribute("value", Integer.toString(maximum));
            }
        }
    }

    private static void handleArray(Element nodeElem, JsonNode jsonNode) {
//        //First build the outer container.
//        final Element outerComplexType = createXsdElement(nodeElem, "complexType");
//        outerComplexType.setAttribute("name", "OuterContainer");
//        final Element outerSequence = createXsdElement(outerComplexType, "sequence");
//        final Element element = createXsdElement(outerSequence, "element");
//        element.setAttribute("name", "Inner");
//        element.setAttribute("type", "InnterType");


        final JsonNode arrItems = jsonNode.path("items");
        String suggestedArrayElementName="dummyArrayElement_"+nodeElem.getAttribute("name");
        if(arrItems.has("description"))
        	suggestedArrayElementName=arrItems.get("description").toString().replaceAll("\\s","");
        
        System.out.println("********************** Manual changes required **********************");
        System.out.println("Providing dummy element name : "+suggestedArrayElementName+" name inside 'array'.");
        System.out.println("***********************************End**********************************");
        
//		final String arrayXsdType = getType(arrItems.path("type").textValue(), arrItems.path("format").textValue());
        final String arrayXsdType = determineXsdType(arrItems.path("type").textValue(), arrItems);
        final Element complexType = createXsdElement(nodeElem, "complexType");
        final Element sequence = createXsdElement(complexType, "sequence");
        final Element arrElem = createXsdElement(sequence, "element");
        if (arrayXsdType.equals("reference")) {
            handleReference(arrElem, arrItems);
        } else if (arrayXsdType.equals("object")) {
            handleObject(arrElem, arrItems);
        } else {
            arrElem.setAttribute("name", "item");
            arrElem.setAttribute("type", arrayXsdType);
        }
        // TODO: Set restrictions for the array type, and possibly recurse into the type if "object"

        // Minimum items
        final Integer minItems = getIntVal(jsonNode, "minItems");
        arrElem.setAttribute("minOccurs", minItems != null ? Integer.toString(minItems) : "0");

        // Max Items
        final Integer maxItems = getIntVal(jsonNode, "maxItems");
        arrElem.setAttribute("maxOccurs", maxItems != null ? Integer.toString(maxItems) : "unbounded");
        
        if(arrElem.hasAttribute("maxOccurs") || arrElem.hasAttribute("minOccurs"))
        	arrElem.setAttribute("name", suggestedArrayElementName.replaceAll("\"", ""));
        

    }

    private static String determineXsdType(String key, JsonNode node) {
    	if(key==null)
    		System.out.println("Error: Malformed JSON array.Please remove the '[' / ']' from the array.");
    	if(key.toLowerCase().equals("oneof") && node.toString().contains("[")){
    		return "object";
    	}
        String jsonType = node.path("type").textValue();
        final String jsonFormat = node.path("format").textValue();
        final boolean isEnum = node.get("enum") != null;
        final boolean isRef = node.get("$ref") != null;
        if (isRef) {
            return "reference";
        } else if (isEnum) {
            return "enum";
        } else {
            Assert.notNull(jsonType, "type must be specified on node '" + key + "': " + node);
            final String xsdType = getType(jsonType, jsonFormat);
            Assert.notNull(xsdType, "Unable to determine XSD type for json type=" + jsonType + ", format=" + jsonFormat);
            return xsdType;
        }

    }

    private static Integer getIntVal(JsonNode node, String attribute) {
        return node.get(attribute) != null ? node.get(attribute).intValue() : null;
    }

    private static Element createXsdElement(Node element, String name) {
        return XmlUtil.createXsdElement(element, name);
    }

    private static Attr createXsdAttr(Node element, String name) {
        return XmlUtil.createXsdAttr(element, name);
    }

    private static String getType(String type, String format) {
        final String key = (type + (format != null ? ("|" + format) : "")).toLowerCase();
        final String retVal = typeMapping.get(key);
        return retVal;
    }

    private static List<String> getRequiredList(JsonNode jsonNode) {
        if (jsonNode.path("required").isMissingNode()) {
            return Collections.emptyList();
        }
        Assert.isTrue(jsonNode.path("required").isArray(), "required must have type: string array");
        List<String> requiredList = new ArrayList<>();
        for (JsonNode requiredField : jsonNode.withArray("required")) {
            Assert.isTrue(requiredField.isTextual(), "required must be string");
            requiredList.add(requiredField.asText());
        }
        return requiredList;
    }
}
