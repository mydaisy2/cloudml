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
package eu.remics.autoscalar;

import java.io.*;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.FileUtils;
import org.cloudml.codecs.JsonCodec;
import org.cloudml.codecs.commons.Codec;
import org.cloudml.core.CloudMLElement;
import org.cloudml.core.DeploymentModel;
import org.cloudml.core.Node;
import org.cloudml.core.NodeInstance;

class Bridge {

    private static final Bridge bridge = new Bridge();

    public static void toXML(DeploymentModel model, String path) {
        try {
            String config = FileUtils.readFileToString(new File(bridge.getClass().getResource("/config.xmlt").toURI()));
            final String component = FileUtils.readFileToString(new File(bridge.getClass().getResource("/component.xmlt").toURI()));
            final String group = FileUtils.readFileToString(new File(bridge.getClass().getResource("/group.xmlt").toURI()));

            Map<String, Node> groups = new HashMap<String, Node>();

            for (Node n : model.getNodeTypes().values()) {
                final String groupName = n.getProperty("groupName");
                System.out.println(n.getName() + ": " + groupName);
                if (groupName != null) {
                    final String minServer = (n.getProperty("minServer") != null) ? n.getProperty("minServer") : "1";
                    final String maxServer = (n.getProperty("maxServer") != null) ? n.getProperty("maxServer") : "3";
                    final String deltaUp = (n.getProperty("deltaUp") != null) ? n.getProperty("deltaUp") : "1";
                    final String deltaDown = (n.getProperty("deltaDown") != null) ? n.getProperty("deltaDown") : "1";
                    final String cpuUp = (n.getProperty("cpuUp") != null) ? n.getProperty("deltaUp") : "70";
                    final String cpuDown = (n.getProperty("cpuDown") != null) ? n.getProperty("cpuDown") : "35";

                    String newGroup = new String(group);

                    newGroup = newGroup.replace("<!--NAME-->", groupName);
                    newGroup = newGroup.replace("<!--MIN_SERVERS-->", minServer);
                    newGroup = newGroup.replace("<!--MAX_SERVERS-->", maxServer);
                    newGroup = newGroup.replace("<!--DELTA_UP-->", deltaUp);
                    newGroup = newGroup.replace("<!--DELTA_DOWN-->", deltaDown);
                    newGroup = newGroup.replace("<!--CPU_UP-->", cpuUp);
                    newGroup = newGroup.replace("<!--CPU_DOWN-->", cpuDown);

                    config = config.replace("<!-- GROUP -->", "<!-- GROUP -->\n" + newGroup + "\n");

                    groups.put(groupName, n);
                }
            }

            config = config.replace("<!-- GROUP -->", "");

            for (NodeInstance ni : model.getNodeInstances()) {
                final String scalingGroup = (ni.getType().getProperty("groupName") != null) ? ni.getType().getProperty("groupName") : "fixed";;

                String newComponent = new String(component);
                newComponent = newComponent.replace("<!--ID-->", ni.getName());
                newComponent = newComponent.replace("<!--IP-->", (!(ni.getPublicAddress() == null || ni.getPublicAddress().equals(""))) ? ni.getPublicAddress() : "to be populated when resource has been booked!");
                newComponent = newComponent.replace("<!--TYPE-->", (ni.getProperty("ProviderInstanceType") != null ) ? ni.getProperty("ProviderInstanceType") : "to be populated when resource has been booked!");
                newComponent = newComponent.replace("<!--GROUP-->", scalingGroup);

                config = config.replace("<!-- COMPONENT -->", "<!-- COMPONENT -->\n" + newComponent + "\n");
            }

            config = config.replace("<!-- COMPONENT -->", "");

            System.out.println(config);

            FileUtils.writeStringToFile(new File(path), config);
        } catch (URISyntaxException ex) {
            Logger.getLogger(Bridge.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Bridge.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public static void main(String args[]) {
        InputStream stream = null;
        try {
            Codec codec = new JsonCodec();
            //stream = new FileInputStream(new File(bridge.getClass().getResource("/ut_load_balancing_trial.json").toURI()));
            stream = new FileInputStream(new File(bridge.getClass().getResource("/sensapp.json").toURI()));
            CloudMLElement model = codec.load(stream);
            toXML((DeploymentModel) model, "component_" + System.currentTimeMillis() + ".xml");
        } catch (URISyntaxException ex) {
            Logger.getLogger(Bridge.class.getName()).log(Level.SEVERE, null, ex);
        } catch (FileNotFoundException ex) {
            Logger.getLogger(Bridge.class.getName()).log(Level.SEVERE, null, ex);
        } finally {
            try {
                stream.close();
            } catch (IOException ex) {
                Logger.getLogger(Bridge.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
}