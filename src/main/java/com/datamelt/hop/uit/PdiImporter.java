/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.datamelt.hop.uit;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.datamelt.hop.utils.Constants;
import com.datamelt.hop.utils.FileUtils;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class PdiImporter
{
	private int fileType;

	private HashMap<String, String> replacementsKtrFile = Constants.getXmlKtrReplacementMap();
	private HashMap<String, String> replacementsKjbFile = Constants.getXmlKjbReplacementMap();
	private HashMap<String, String> replacementsKjbFileText = Constants.getXmlKjbTextReplacementMap();
	private HashMap<String, String> replacementsKjbFilePartialText = Constants.getXmlKjbPartialTextReplacementMap();
	
	private VelocityContext context;
	private Template databaseTemplate;
	private static final Logger logger = LogManager.getLogger(PdiImporter.class);
	
	private String inputfolderFiles;
	private String outputfolderFiles;
	private String outputfolderDatabaseConnections;
	
	public int processFile(File file)
	{
		int errors = 0;
		
		// parse the file
		try
		{
			Document document = parseDocument(file);
			
			// check file type by scanning the tags
			determineFileType(document);
			
			if(fileType==Constants.FILE_TYPE_KJB)
			{
				// rename nodes according to map
				renameNodes(document);
		        
				// process text values
				processText(document);
				
				// process partial text values
				processPartialText(document);
				
		        // write document only if no errors happened when trying to
		        // create the database connection files
		        if(errors==0)
		        {
		        	String newFilePathAndName = FileUtils.getFileOutputFolder(file,inputfolderFiles, outputfolderFiles);
		        	File newFile = new File(FileUtils.migrateFilename(newFilePathAndName, fileType));
		        	FileUtils.createFolder(newFile.getAbsolutePath(),newFile.getName());
		        	if(!newFile.exists())
		        	{
		        		writeDocument(newFile,document);
		        	}
		        	else
		        	{
		        		logger.warn("file already exists. no file generated: " + newFile.getName());
		        	}
		        }
			}
			else if(fileType==Constants.FILE_TYPE_KTR)
			{
				// rename nodes according to map
				renameNodes(document);
		        
		        // modify the connection node and write database metadata file(s)
		        processConnectionNode(document);
				
		        // process partial text values
				processPartialText(document);
				
		        // write document only if no errors happened when trying to
		        // create the database connection files
		        if(errors==0)
		        {
		        	String newFilePathAndName = FileUtils.getFileOutputFolder(file,inputfolderFiles, outputfolderFiles);
		        	
		        	File newFile = new File(FileUtils.migrateFilename(newFilePathAndName, fileType));
		        	FileUtils.createFolder(newFile.getAbsolutePath(),newFile.getName());
		        	if(!newFile.exists())
		        	{
		        		writeDocument(newFile,document);
		        	}
		        	else
		        	{
		        		logger.warn("file already exists. no file generated: " + newFile.getName());
		        	}
		        }
			}
			else
			{
				logger.warn("file is not a .kjb or .ktr file: " + file.getName());
			}
		}
		catch(Exception ex)
		{
			logger.error("the file could not be parsed: " + file.toString() + ", error: " + ex.getMessage());
			errors++;
		}
		
        return errors;
	}
	
	private Document parseDocument(File file) throws Exception
	{
		logger.debug("parsing file: " + file.getName());
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
	}
	
	private void determineFileType(Document document)
	{
		// check if file has a job tag
		NodeList nodesJob = document.getElementsByTagName("job");
		NodeList nodesTransformation = document.getElementsByTagName("transformation");
		
		if(nodesJob.getLength()>0)
		{
			logger.debug("determined file type: kettle job file" );
			fileType = Constants.FILE_TYPE_KJB;
		}
		else if(nodesTransformation.getLength()>0)
		{
			logger.debug("determined file type: kettle transformation file" );
			fileType = Constants.FILE_TYPE_KTR;
		}
	}
	
	private void writeDocument(File newFile,Document document) throws Exception
	{
		logger.debug("writing file: " + newFile.getName());

		Transformer transformer = TransformerFactory.newInstance().newTransformer();
		Source input = new DOMSource(document);
		Result output = new StreamResult(newFile);
		transformer.transform(input, output);
	}
	
	private void writeDatabaseMetadataFile(HashMap<String, String> connectionAttributes) throws Exception
	{
		String filename = connectionAttributes.get(Constants.TAG_CONNECTION_CHILD_NAME);
		// correct the filename if it contains invalid characters
		String correctedFilename = FileUtils.correctInvalidFilename(filename);
		
		File file = new File(outputfolderDatabaseConnections + "/" + correctedFilename + ".xml");
		if(!file.exists())
		{
			StringWriter sw = new StringWriter();
			
			context.remove(Constants.DATABASE_METADATA_TEMPLATE_OBJECT_KEY);
			context.put( Constants.DATABASE_METADATA_TEMPLATE_OBJECT_KEY, connectionAttributes);
	
			logger.debug("merging template and connection attributes");
			databaseTemplate.merge( context, sw );
	
			
			logger.debug("writing database metadata file: " + file.getName());
			try (PrintStream out = new PrintStream(new FileOutputStream(file))) 
			{
				out.print(sw);
			}
		}
		else
		{
			logger.warn("database metadata file already exists. no file generated: " + file.getName());
		}
	}
	
	private void renameNodes(Document document)
	{
		if(fileType== Constants.FILE_TYPE_KJB)
		{
			for(String key:replacementsKjbFile.keySet())
	        {
	        	renameNode(document,key,replacementsKjbFile.get(key));
	        }
		}
		else if(fileType== Constants.FILE_TYPE_KTR)
		{
			for(String key:replacementsKtrFile.keySet())
	        {
	        	renameNode(document,key,replacementsKtrFile.get(key));
	        }
		}
		
	}
	
	private void renameNode(Document document, String from, String to)
	{
		logger.debug("renaming nodes from: [" + from + "] - to: [" + to + "]");
		NodeList nodes = document.getElementsByTagName(from);
		logger.debug("nodes found: " + nodes.getLength() );
		for (int i = 0; i < nodes.getLength(); i++) 
        {
            Node node = nodes.item(i);
            document.renameNode(node, null, to);
        }
	}
	
	private int processText(Document document) throws Exception
	{
		int errors = 0;
		NodeList nodes = document.getElementsByTagName(Constants.TAG_JOB_TAG_ACTION);
		
		for (int i = 0; i < nodes.getLength(); i++) 
        {
        	Node mainNode = nodes.item(i);
        	if (mainNode.getNodeType() == Node.ELEMENT_NODE) 
        	{
	        	Element element = (Element) mainNode;
	        	NodeList typeNode = element.getElementsByTagName(Constants.TAG_JOB_TAG_ACTION_TYPE);
	        	if(typeNode.getLength()==1)
	        	{
	        		String currentValue = typeNode.item(0).getTextContent();
	        		String replacement = replacementsKjbFileText.get(currentValue);
        			if(replacement!=null)
        			{
        				typeNode.item(0).setTextContent(replacement);
        			}
	        	}
        	}
        }
		
		return errors;
	}
	
	private int processPartialText(Document document) throws Exception
	{
		int errors = 0;
		NodeList nodes = document.getElementsByTagName("*");
		
		for (int i = 0; i < nodes.getLength(); i++) 
        {
        	Node mainNode = nodes.item(i);
        	String nodeText = mainNode.getTextContent();
        	if(nodeText!=null)
        	{
        		for(String pattern : replacementsKjbFilePartialText.keySet())
        		{
        			if(nodeText.matches(".*" + pattern + ".*"))
	        		{
	        			String replacementText = replacementsKjbFilePartialText.get(pattern);
	        			String newText = nodeText.replaceAll(pattern, replacementText);
	        			mainNode.setTextContent(newText);
	        		}
        		}
        	}
        	// loop over subnodes
			Element element = (Element) mainNode;
			NodeList subNodeList = element.getElementsByTagName("*");
			if(subNodeList.getLength()>0)
			{
    			for(int j=0;j<subNodeList.getLength();j++)
    			{
    				Node subNode = subNodeList.item(j);
    				String subNodeText = subNode.getTextContent();
    				for(String pattern : replacementsKjbFilePartialText.keySet())
            		{
	        			if(subNodeText.matches(".*" + pattern + ".*"))
		        		{
		        			String replacementText = replacementsKjbFilePartialText.get(pattern);
		        			String newText = subNodeText.replaceAll(pattern, replacementText);
		        			subNode.setTextContent(newText);
		        		}
            		}
    			}
			}
        }
		
		return errors;
	}
	
	private int processConnectionNode(Document document) throws Exception
	{
		int errors = 0;
		ArrayList<Node> toBeRemovedNodes = new ArrayList<>();
		
		// get all connections from the document
		NodeList nodes = document.getElementsByTagName(Constants.TAG_CONNECTION);
        for (int i = 0; i < nodes.getLength(); i++) 
        {
        	Node node = nodes.item(i);
        	Element connection = (Element) node;
        	if(connection.hasChildNodes() && connection.getChildNodes().getLength()>1)
        	{
        		HashMap<String, String> connectionAttributes = new HashMap<>();

        		// type and name of the connection from the source file
        		String pdiType = connection.getElementsByTagName(Constants.TAG_CONNECTION_CHILD_TYPE).item(0).getTextContent();
        		String pdiName = connection.getElementsByTagName(Constants.TAG_CONNECTION_CHILD_NAME).item(0).getTextContent();

        		logger.debug("processing connection node. type: [" + pdiType + "] , name: [" + pdiName + "]");

        		if(Constants.getDatabaseConnectionMap().containsKey(pdiType))
        		{
	        		Connection mappedConnection = Constants.getDatabaseConnectionMap().get(pdiType);
	        		
	        		connectionAttributes.put(Constants.TAG_CONNECTION_CHILD_NAME, pdiName);
	        		connectionAttributes.put(Constants.DATABASE_METADATA_PLUGIN_ID, pdiType);
	        		connectionAttributes.put(Constants.DATABASE_METADATA_PLUGIN_NAME, mappedConnection.getType());
	        		
	        		connectionAttributes.put(Constants.TAG_CONNECTION_CHILD_SERVER, connection.getElementsByTagName(Constants.TAG_CONNECTION_CHILD_SERVER).item(0).getTextContent());
	        		connectionAttributes.put(Constants.TAG_CONNECTION_CHILD_ACCESS_TYPE, "0");
	        		connectionAttributes.put(Constants.DATABASE_METADATA_POJO, mappedConnection.getDatabaseMeta());
	        		connectionAttributes.put(Constants.TAG_CONNECTION_CHILD_DATABASE, connection.getElementsByTagName(Constants.TAG_CONNECTION_CHILD_DATABASE).item(0).getTextContent());
	        		connectionAttributes.put(Constants.TAG_CONNECTION_CHILD_PORT, connection.getElementsByTagName(Constants.TAG_CONNECTION_CHILD_PORT).item(0).getTextContent());
	        		connectionAttributes.put(Constants.TAG_CONNECTION_CHILD_USERNAME, connection.getElementsByTagName(Constants.TAG_CONNECTION_CHILD_USERNAME).item(0).getTextContent());
	        		connectionAttributes.put(Constants.TAG_CONNECTION_CHILD_PASSWORD, connection.getElementsByTagName(Constants.TAG_CONNECTION_CHILD_PASSWORD).item(0).getTextContent());
	       		
	        		writeDatabaseMetadataFile(connectionAttributes);
	        		
	        		// if we have corrected the filename because it contained invalid characters, then we need
	        		// to also replace the reference in the xml file

        	
	        		// capture which node was used - this node will be removed later from the xml document.
	        		toBeRemovedNodes.add(node);
        		}
        		else
        		{
        			errors++;
        			logger.error("unknown database type: " + pdiType);
        			logger.error("no database metadata file has been written for this type: " + pdiType);
        		}
        	}
        }
        
        // remove nodes that have been converted to metadata files for hop
        if(errors==0)
        {
	        for (int j = 0; j < toBeRemovedNodes.size(); j++) 
	        {
	        	Node toBeRemoved = toBeRemovedNodes.get(j);
	        	toBeRemoved.getParentNode().removeChild(toBeRemoved);
	        }
        }
        
        return errors;
	}

	public VelocityContext getContext() 
	{
		return context;
	}

	public void setVelocityContext(VelocityContext context) 
	{
		this.context = context;
	}
	
	public void setVelocityTemplate(Template databaseTemplate) 
	{
		this.databaseTemplate = databaseTemplate;
	}

	public String getInputfolderFiles() 
	{
		return inputfolderFiles;
	}

	public void setInputfolderFiles(String inputfolderFiles) 
	{
		this.inputfolderFiles = inputfolderFiles;
	}
	
	public String getOutputfolderFiles() 
	{
		return outputfolderFiles;
	}

	public void setOutputfolderFiles(String outputfolderFiles) 
	{
		this.outputfolderFiles = outputfolderFiles;
	}

	public String getOutputfolderDatabaseConnections() 
	{
		return outputfolderDatabaseConnections;
	}

	public void setOutputfolderDatabaseConnections(String outputfolderDatabaseConnections) 
	{
		this.outputfolderDatabaseConnections = outputfolderDatabaseConnections;
	}
	
}
