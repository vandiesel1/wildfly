/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.controller;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEPLOYMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEPLOYMENT_OVERLAY;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PROFILE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_CONFIG;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SOCKET_BINDING_GROUP;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jboss.as.controller.access.HostEffect;
import org.jboss.as.controller.access.ServerGroupEffect;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;

/**
 * Tracks what server groups are associated with various model resources.
 *
 * @author Brian Stansberry (c) 2013 Red Hat Inc.
 */
class HostServerGroupTracker {

    static class HostServerGroupEffect implements HostEffect, ServerGroupEffect {

        private static final Set<String> EMPTY = Collections.emptySet();

        private final PathAddress address;
        private final Set<String> serverGroupEffects;
        private final Set<String> hostEffects;
        private final boolean unassigned;

        private static HostServerGroupEffect forGlobal(PathAddress address) {
            return new HostServerGroupEffect(address, (Set<String>) null, null, false);
        }

        private static HostServerGroupEffect forDomain(PathAddress address,
                                                       Set<String> serverGroupEffects) {
            return new HostServerGroupEffect(address,
                    serverGroupEffects == null ? EMPTY : serverGroupEffects, null, false);
        }

        private static HostServerGroupEffect forUnassignedDomain(PathAddress address) {
            return new HostServerGroupEffect(address, EMPTY, null, true);
        }

        private static HostServerGroupEffect forServerGroup(PathAddress address, String serverGroup) {
            return new HostServerGroupEffect(address, Collections.singleton(serverGroup), null, false);
        }

        private static HostServerGroupEffect forHost(PathAddress address, String hostEffect) {
            return new HostServerGroupEffect(address, (Set<String>) null, hostEffect, false);
        }


        static HostServerGroupEffect forServer(PathAddress address, String serverGroupEffect, String hostEffect) {
            assert serverGroupEffect != null : "serverGroupEffect is null";
            return new HostServerGroupEffect(address, Collections.singleton(serverGroupEffect), hostEffect, false);
        }


        private HostServerGroupEffect(PathAddress address,
                                      Set<String> serverGroupEffects, String hostEffect, boolean unassigned) {
            this.address = address;
            this.serverGroupEffects = serverGroupEffects;
            this.unassigned = unassigned;
            this.hostEffects = hostEffect == null ? null : Collections.singleton(hostEffect);
        }

        @Override
        public PathAddress getResourceAddress() {
            return address;
        }

        @Override
        public boolean isServerGroupEffectGlobal() {
            return serverGroupEffects == null;
        }

        @Override
        public boolean isServerGroupEffectUnassigned() {
            return unassigned;
        }

        @Override
        public Set<String> getAffectedServerGroups() {
            return serverGroupEffects;
        }

        @Override
        public boolean isHostEffectGlobal() {
            return hostEffects == null;
        }

        @Override
        public Set<String> getAffectedHosts() {
            return hostEffects;
        }
    }

    private boolean requiresMapping = true;
    private final Map<String, Set<String>> profilesToGroups = new HashMap<String, Set<String>>();
    private final Map<String, Set<String>> socketsToGroups = new HashMap<String, Set<String>>();
    private final Map<String, Set<String>> deploymentsToGroups = new HashMap<String, Set<String>>();
    private final Map<String, Set<String>> overlaysToGroups = new HashMap<String, Set<String>>();

    HostServerGroupEffect getHostServerGroupEffects(PathAddress address, ModelNode operation, Resource root) {

        if (address.size() > 0) {

            PathElement firstElement = address.getElement(0);
            String type = firstElement.getKey();
            // Not a switch to ease EAP 6 backport
            if (HOST.equals(type)) {
                String hostName = firstElement.getValue();
                if (address.size() > 1) {
                    PathElement secondElement = address.getElement(1);
                    String lvlone = secondElement.getKey();
                    if (SERVER_CONFIG.equals(lvlone) || SERVER.equals(lvlone)) {
                        Resource hostResource = root.getChild(firstElement);
                        if (hostResource != null) {
                            String serverGroup = null;
                            Resource serverConfig = hostResource.getChild(PathElement.pathElement(SERVER_CONFIG, secondElement.getValue()));
                            if (serverConfig != null) { // may be null if hostName is not the local host
                                ModelNode model = serverConfig.getModel();
                                if (model.hasDefined(GROUP)) {
                                    serverGroup = model.get(GROUP).asString();
                                }
                            }
                            if (serverGroup == null && address.size() == 2 && SERVER_CONFIG.equals(lvlone)
                                    && ADD.equals(operation.require(OP).asString())) {
                                serverGroup = operation.get(GROUP).asString();
                            }

                            if (serverGroup != null) {
                                return HostServerGroupEffect.forServer(address, serverGroup, hostName);
                            } // else may be null if hostName is not the local host.
                              // We checked it's not a server-config add so assume it's a read and just provide the
                              // forHost response, which will be acceptable for a read for any server group scoped role
                        } // else not the local host. Can only be a read, so just use the forHost response,
                          // which will be acceptable for a read for any server group scoped role
                    }
                }
                return HostServerGroupEffect.forHost(address, hostName);
            } else if (PROFILE.equals(type)) {
                return getDomainEffect(address, firstElement.getValue(), profilesToGroups, root);
            } else if (SOCKET_BINDING_GROUP.equals(type)) {
                return getDomainEffect(address, firstElement.getValue(), socketsToGroups, root);
            } else if (SERVER_GROUP.equals(type)) {
                return HostServerGroupEffect.forServerGroup(address, firstElement.getValue());
            } else if (DEPLOYMENT.equals(type)) {
                return getDomainEffect(address, firstElement.getValue(), deploymentsToGroups, root);
            } else if (DEPLOYMENT_OVERLAY.equals(type)) {
                return getDomainEffect(address, firstElement.getValue(), overlaysToGroups, root);
            }
        }

        return HostServerGroupEffect.forGlobal(address);
    }

    synchronized void invalidate() {
        requiresMapping = true;
        profilesToGroups.clear();
        socketsToGroups.clear();
        deploymentsToGroups.clear();
        overlaysToGroups.clear();
    }

    private synchronized HostServerGroupEffect getDomainEffect(PathAddress address, String key,
                                                               Map<String, Set<String>> map, Resource root) {
        if (requiresMapping) {
            map(root);
            requiresMapping = false;
        }
        Set<String> mapped = map.get(key);
        return mapped != null ? HostServerGroupEffect.forDomain(address, mapped)
                              : HostServerGroupEffect.forUnassignedDomain(address);
    }

    /** Only call with monitor for 'this' held */
    private void map(Resource root) {

        for (Resource.ResourceEntry serverGroup : root.getChildren(SERVER_GROUP)) {
            String serverGroupName = serverGroup.getName();
            ModelNode serverGroupModel = serverGroup.getModel();
            String profile = serverGroupModel.require(PROFILE).asString();
            store(serverGroupName, profile, profilesToGroups);
            String socketBindingGroup = serverGroupModel.require(SOCKET_BINDING_GROUP).asString();
            store(serverGroupName, socketBindingGroup, socketsToGroups);

            for (Resource.ResourceEntry deployment : serverGroup.getChildren(DEPLOYMENT)) {
                store(serverGroupName, deployment.getName(), deploymentsToGroups);
            }

            for (Resource.ResourceEntry overlay : serverGroup.getChildren(DEPLOYMENT_OVERLAY)) {
                store(serverGroupName, overlay.getName(), overlaysToGroups);
            }

        }
    }

    private static void store(String serverGroup, String key, Map<String, Set<String>> map) {
        Set<String> set = map.get(key);
        if (set == null) {
            set = new HashSet<String>();
            map.put(key, set);
        }
        set.add(serverGroup);
    }
}
