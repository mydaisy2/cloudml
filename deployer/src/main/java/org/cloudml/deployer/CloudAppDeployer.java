/**
 * This file is part of CloudML [ http://cloudml.org ]
 *
 * Copyright (C) 2012 - SINTEF ICT
 * Contact: Franck Chauvel <franck.chauvel@sintef.no>
 *
 * Module: root
 *
 * CloudML is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * CloudML is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General
 * Public License along with CloudML. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package org.cloudml.deployer;


import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.cloudml.core.*;
import org.jclouds.compute.RunScriptOnNodesException;
import org.jclouds.compute.domain.ComputeMetadata;
import org.jclouds.compute.domain.NodeMetadata;

/*
 * The deployment Engine
 * author: Nicolas Ferry
 */

public class CloudAppDeployer {

	private static final Logger journal = Logger.getLogger(JCloudsConnector.class.getName());
	
	ArrayList<ArtefactInstance> alreadyDeployed=new ArrayList<ArtefactInstance>();
	ArrayList<ArtefactInstance> alreadyStarted=new ArrayList<ArtefactInstance>();

	private DeploymentModel currentModel;

	public CloudAppDeployer(){}


	/**
	 * Deploy from a deployment model
	 * @param dm a deployment model
	 */
	public void deploy(DeploymentModel targetModel){
		alreadyDeployed=new ArrayList<ArtefactInstance>();
		alreadyStarted=new ArrayList<ArtefactInstance>();
		if(currentModel == null){
			journal.log(Level.INFO, ">> First deployment...");
			this.currentModel=targetModel;
			
			// Provisioning nodes
			provisioning(targetModel.getNodeInstances());

			// Deploying on nodes
			// TODO: need to be recursive
			prepareArtefacts(targetModel.getArtefactInstances(),targetModel.getBindingInstances());

			//Configure the artefacts with the bindings
			configureWithBindings(targetModel.getBindingInstances());

			//configuration process at SaaS level
			configureSaas(targetModel.getArtefactInstances());

		}else{
			journal.log(Level.INFO, ">> Updating a deployment...");
			DeploymentModelComparator diff=new DeploymentModelComparator(currentModel, targetModel);
			diff.compareDeploymentModel();

			//Added stuff
			provisioning(diff.getAddedNodes());
			prepareArtefacts(diff.getAddedArtefacts(), targetModel.getBindingInstances());
			configureWithBindings(diff.getAddedBindings());
			configureSaas(diff.getAddedArtefacts());

			//remove stuff
			unconfigureBindings(diff.getRemovedBindings());
			stopArtefacts(diff.getRemovedArtefacts());
			teminateNodes(diff.getRemovedNodes());
			updateCurrentModel(diff);
		}
	}

	/**
	 * Update the currentModel with the targetModel and preserve all the CPSM metadata
	 * @param diff a model comparator
	 */
	public void updateCurrentModel(DeploymentModelComparator diff){
		currentModel.getArtefactInstances().removeAll(diff.getRemovedArtefacts());
		currentModel.getBindingInstances().removeAll(diff.getRemovedBindings());
		currentModel.getNodeInstances().removeAll(diff.getRemovedNodes());
		
		currentModel.getArtefactInstances().addAll(diff.getAddedArtefacts());
		currentModel.getBindingInstances().addAll(diff.getAddedBindings());
		currentModel.getNodeInstances().addAll(diff.getAddedNodes());
	}

	/**
	 * Prepare the artefacts before their start. Retrieves their resources, builds their PaaS and installs them
	 * @param dm a deployment model
	 */
	private void prepareArtefacts(List<ArtefactInstance> artefacts, List<BindingInstance> bindings){
		for(ArtefactInstance x : artefacts){
			prepareAnArtefact(x, artefacts, bindings);
		}
	}

	/**
	 * Prepare an artefact before it starts. Retrieves its resources, builds its PaaS and installs it
	 * @param x an ArtefactInstance
	 * @param dm the deployment model used to build the artefact's PaaS
	 */
	private void prepareAnArtefact(ArtefactInstance x, List<ArtefactInstance> artefacts, List<BindingInstance> bindings){
		JCloudsConnector jc;
		if(!alreadyDeployed.contains(x) && (x.getDestination() != null)){
			NodeInstance ownerNode = x.getDestination();
			Node n=ownerNode.getType();
			jc=new JCloudsConnector(n.getProvider().getName(), n.getProvider().getLogin(), n.getProvider().getPasswd());
			jc.execCommand(ownerNode.getId(), x.getType().getResource().getRetrievingResourceCommand(),"ubuntu",n.getPrivateKey());
			alreadyDeployed.add(x);

			buildPaas(x,bindings);

			jc.execCommand(ownerNode.getId(), x.getType().getResource().getDeployingResourceCommand(),"ubuntu",n.getPrivateKey());
			jc.closeConnection();
		}
	}


	/**
	 * Build the paas of an artefact instance
	 * @param x An artefactInstance
	 */
	private void buildPaas(ArtefactInstance x, List<BindingInstance> bindings){
		NodeInstance ownerNode = x.getDestination();
		Node n=ownerNode.getType();

		JCloudsConnector jc;
		jc=new JCloudsConnector(n.getProvider().getName(), n.getProvider().getLogin(), n.getProvider().getPasswd());


		for(BindingInstance bi : bindings){
			if(!bi.getClient().getType().getIsOptional() && x.getRequired().contains(bi.getClient())){
				ServerPortInstance p=bi.getServer();
				if(!alreadyDeployed.contains(p.getOwner())){
					jc.execCommand(ownerNode.getId(), p.getOwner().getType().getResource().getRetrievingResourceCommand() ,"ubuntu",n.getPrivateKey());
					jc.execCommand(ownerNode.getId(), p.getOwner().getType().getResource().getDeployingResourceCommand(),"ubuntu",n.getPrivateKey());

					String configurationCommand=p.getOwner().getType().getResource().getConfigurationResourceCommand();
					String startCommand=p.getOwner().getType().getResource().getStartResourceCommand();
					configureAndStart(jc, n, ownerNode, configurationCommand, startCommand);
					alreadyDeployed.add(p.getOwner());
					alreadyStarted.add(p.getOwner());
				}
			}
		}
		jc.closeConnection();

	}

	

	/**
	 * Configure and start SaaS artefacts
	 * @param dm a deployment model
	 */
	private void configureSaas(List<ArtefactInstance> artefacts){
		JCloudsConnector jc;
		for(ArtefactInstance x : artefacts){
			if(!alreadyStarted.contains(x)){
				NodeInstance ownerNode = x.getDestination();
				Node n=ownerNode.getType();
				jc=new JCloudsConnector(n.getProvider().getName(), n.getProvider().getLogin(), n.getProvider().getPasswd());

				String configurationCommand=x.getType().getResource().getConfigurationResourceCommand();
				String startCommand= x.getType().getResource().getStartResourceCommand();
				configureAndStart(jc, n, ownerNode, configurationCommand, startCommand);
				alreadyStarted.add(x);
				jc.closeConnection();
			}
		}
	}


	/**
	 * Configure and start an artefact
	 * @param jc a connector
	 * @param n A node type
	 * @param ni a node instance
	 * @param configurationCommand the command to configure the artefact, parameters are: IP IPDest portDest
	 * @param startCommand the command to start the artefact
	 */
	private void configureAndStart(JCloudsConnector jc, Node n, NodeInstance ni, String configurationCommand, String startCommand){
		if(!configurationCommand.equals(""))
			jc.execCommand(ni.getId(), configurationCommand,"ubuntu",n.getPrivateKey());
		if(!startCommand.equals(""))
			jc.execCommand(ni.getId(), startCommand,"ubuntu",n.getPrivateKey());
	}

	/**
	 * Provision the nodes and upload the model with informations about the node
	 * @param dm 
	 * 			A deployment model
	 */
	private void provisioning(List<NodeInstance> nodes){
		for(NodeInstance n : nodes){
			provisionANode(n);
		}
	}

	/**
	 * Provision a node
	 * @param n a NodeInstance
	 */
	private void provisionANode(NodeInstance n){
		Provider p=n.getType().getProvider();
		JCloudsConnector jc=new JCloudsConnector(p.getName(), p.getLogin(), p.getPasswd());
		ComputeMetadata cm= jc.getNodeByName(n.getName());
		/* UPDATE THE MODEL */
		if(cm == null){
			jc.createInstance(n);
		}else{
			n.setPublicAddress(jc.getNodeById(cm.getId()).getPublicAddresses().iterator().next());
			n.setId(cm.getId());
		}
		jc.closeConnection();
	}


	/**
	 * Configure Artefacts according to the bindings
	 * @param dm a deployment model
	 */
	private void configureWithBindings(List<BindingInstance> bindings){
		//Configure on the basis of the bindings
		//parameters transmitted to the configuration scripts are "ip ipDestination portDestination"
		for(BindingInstance bi : bindings){
			if(bi.getClient().getType().getIsRemote()){
				ClientPortInstance client=bi.getClient();
				ServerPortInstance server=bi.getServer();

				Resource clientResource=bi.getType().getClientResource();
				Resource serverResource=bi.getType().getServerResource();

				String destinationIpAddress=server.getOwner().getDestination().getPublicAddress();
				int destinationPortNumber=server.getType().getPortNumber();
				String ipAddress=client.getOwner().getDestination().getPublicAddress();

				//client resources
				configureWithIP(clientResource,client,destinationIpAddress,ipAddress,destinationPortNumber);

				//server resources
				configureWithIP(serverResource,server,destinationIpAddress,ipAddress,destinationPortNumber);
			}
		}
	}

	/**
	 * Configuration with parameters IP, IPDest, PortDest
	 * @param r resource for configuration
	 * @param i port of the component to be configured
	 * @param destinationIpAddress IP of the server
	 * @param ipAddress IP of the client
	 * @param destinationPortNumber port of the server
	 */
	private void configureWithIP(Resource r, ArtefactPortInstance i, String destinationIpAddress, String ipAddress, int destinationPortNumber){
		JCloudsConnector jc;
		if(r != null){
			NodeInstance ownerNode = i.getOwner().getDestination();
			Node n=ownerNode.getType();
			jc=new JCloudsConnector(n.getProvider().getName(), n.getProvider().getLogin(), n.getProvider().getPasswd());
			jc.execCommand(ownerNode.getId(), r.getRetrievingResourceCommand(),"ubuntu",n.getPrivateKey());
			String configurationCommand=r.getConfigurationResourceCommand()+" \""+ipAddress+"\" \""+destinationIpAddress+"\" "+destinationPortNumber;
			configureAndStart(jc, n, ownerNode, configurationCommand, "");
			jc.closeConnection();
		}
	}

	/**
	 * Terminates a set of nodes
	 * @param nodes A list of nodeInstances
	 */
	private void teminateNodes(List<NodeInstance> nodes){
		for(NodeInstance n: nodes){
			terminateNode(n);
		}
	}

	/**
	 * Terminate a node
	 * @param n A node instance to be terminated
	 */
	private void terminateNode(NodeInstance n){
		Provider p=n.getType().getProvider();
		JCloudsConnector jc=new JCloudsConnector(p.getName(), p.getLogin(), p.getPasswd());
		jc.destroyNode(n.getId());
		jc.closeConnection();
	}

	/**
	 * Stop a list of artefacts
	 * @param artefacts a list of ArtefactInstance
	 */
	private void stopArtefacts(List<ArtefactInstance> artefacts){
		for(ArtefactInstance a : artefacts){
			stopArtefact(a);
		}
	}

	/**
	 * Stop a specific artefact instance
	 * @param a An Artefact Instance
	 */
	private void stopArtefact(ArtefactInstance a){
		NodeInstance ownerNode = a.getDestination();
		Node n=ownerNode.getType();
		JCloudsConnector jc=new JCloudsConnector(n.getProvider().getName(), n.getProvider().getLogin(), n.getProvider().getPasswd());
		String stopCommand=a.getType().getResource().getStopResourceCommand();
		jc.execCommand(ownerNode.getId(), stopCommand,"ubuntu",n.getPrivateKey());
		jc.closeConnection();
	}

	private void unconfigureBindings(List<BindingInstance> bindings){
		for(BindingInstance b:bindings){
			unconfigureBinding(b);
		}
	}

	private void unconfigureBinding(BindingInstance b){
		if(b.getClient().getType().getIsRemote()){
			ClientPortInstance client=b.getClient();
			ServerPortInstance server=b.getServer();

			Resource clientResource=b.getType().getClientResource();
			Resource serverResource=b.getType().getServerResource();
			
			//client resources
			unconfigureWithIP(clientResource,client);

			//server resources
			unconfigureWithIP(serverResource,server);
		}
	}
	
	private void unconfigureWithIP(Resource r, ArtefactPortInstance i){
		JCloudsConnector jc;
		if(r != null){
			NodeInstance ownerNode = i.getOwner().getDestination();
			Node n=ownerNode.getType();
			jc=new JCloudsConnector(n.getProvider().getName(), n.getProvider().getLogin(), n.getProvider().getPasswd());
			jc.execCommand(ownerNode.getId(), r.getStopResourceCommand(),"ubuntu",n.getPrivateKey());;
			jc.closeConnection();
		}
	}

}