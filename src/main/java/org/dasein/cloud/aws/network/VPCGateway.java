/**
 * Copyright (C) 2009-2012 enStratus Networks Inc
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.dasein.cloud.aws.network;

import org.apache.log4j.Logger;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.Requirement;
import org.dasein.cloud.aws.AWSCloud;
import org.dasein.cloud.aws.compute.EC2Exception;
import org.dasein.cloud.aws.compute.EC2Method;
import org.dasein.cloud.identity.ServiceAction;
import org.dasein.cloud.network.VPN;
import org.dasein.cloud.network.VPNConnection;
import org.dasein.cloud.network.VPNConnectionState;
import org.dasein.cloud.network.VPNGateway;
import org.dasein.cloud.network.VPNGatewayState;
import org.dasein.cloud.network.VPNProtocol;
import org.dasein.cloud.network.VPNState;
import org.dasein.cloud.network.VPNSupport;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeSet;

@SuppressWarnings("UnusedDeclaration")
public class VPCGateway implements VPNSupport {
    Logger logger = AWSCloud.getLogger(VPCGateway.class);

    private AWSCloud provider;
    
    public VPCGateway(@Nonnull AWSCloud provider) { this.provider = provider; }
    
    @Override
    public void attachToVLAN(@Nonnull String providerVpnId, @Nonnull String providerVlanId) throws CloudException, InternalException {
        ProviderContext ctx = provider.getContext();

        if( ctx == null ) {
            throw new CloudException("No context was configured");
        }
        Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), ELBMethod.ATTACH_VPN_GATEWAY);
        EC2Method method;

        parameters.put("VpcId", providerVlanId);
        parameters.put("VpnGatewayId", providerVpnId);
        method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
            method.invoke();
        }
        catch( EC2Exception e ) {
            logger.error(e.getSummary());
            e.printStackTrace();
            throw new CloudException(e);
        }
    }

    @Override
    public void connectToGateway(@Nonnull String providerVpnId, @Nonnull String toGatewayId) throws CloudException, InternalException {
        VPNGateway gateway = getGateway(toGatewayId);
        VPN vpn = getVPN(providerVpnId);

        if( gateway == null ) {
            throw new CloudException("No such VPN gateway: " + toGatewayId);
        }
        if( vpn == null ) {
            throw new CloudException("No such VPN: " + providerVpnId);
        }
        if( !gateway.getProtocol().equals(vpn.getProtocol()) ) {
            throw new CloudException("VPN protocol mismatch between VPN and gateway: " + vpn.getProtocol() + " vs " + gateway.getProtocol());
        }
        ProviderContext ctx = provider.getContext();

        if( ctx == null ) {
            throw new CloudException("No context was configured");
        }
        Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), ELBMethod.CREATE_VPN_CONNECTION);
        EC2Method method;

        parameters.put("Type", getAWSProtocol(vpn.getProtocol()));
        parameters.put("CustomerGatewayId", gateway.getProviderVpnGatewayId());
        parameters.put("VpnGatewayId", vpn.getProviderVpnId());
        method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
            method.invoke();
        }
        catch( EC2Exception e ) {
            logger.error(e.getSummary());
            e.printStackTrace();
            throw new CloudException(e);
        }
    }

    @Override
    public @Nonnull VPN createVPN(@Nullable String dataCenterId, @Nonnull String name, @Nonnull String description, @Nonnull VPNProtocol protocol) throws CloudException, InternalException {
        ProviderContext ctx = provider.getContext();

        if( ctx == null ) {
            throw new CloudException("No context was configured");
        }
        Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), ELBMethod.CREATE_VPN_GATEWAY);
        EC2Method method;
        NodeList blocks;
        Document doc;

        parameters.put("Type", getAWSProtocol(protocol));
        method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
            doc = method.invoke();
        }
        catch( EC2Exception e ) {
            logger.error(e.getSummary());
            e.printStackTrace();
            throw new CloudException(e);
        }
        blocks = doc.getElementsByTagName("vpnGateway");

        for( int i=0; i<blocks.getLength(); i++ ) {
            Node item = blocks.item(i);
            VPN vpn = toVPN(ctx, item);

            if( vpn != null ) {
                return vpn;
            }
        }
        throw new CloudException("No VPN was created, but no error was reported");
    }

    @Override
    public @Nonnull VPNGateway createVPNGateway(@Nonnull String endpoint, @Nonnull String name, @Nonnull String description, @Nonnull VPNProtocol protocol, @Nonnull String bgpAsn) throws CloudException, InternalException {
        ProviderContext ctx = provider.getContext();

        if( ctx == null ) {
            throw new CloudException("No context was configured");
        }
        Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), ELBMethod.CREATE_CUSTOMER_GATEWAY);
        EC2Method method;
        NodeList blocks;
        Document doc;

        parameters.put("Type", getAWSProtocol(protocol));
        parameters.put("IpAddress", endpoint);
        parameters.put("BgpAsn", bgpAsn);
        method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
            doc = method.invoke();
        }
        catch( EC2Exception e ) {
            logger.error(e.getSummary());
            if( logger.isDebugEnabled() ) {
                e.printStackTrace();
            }
            throw new CloudException(e);
        }
        blocks = doc.getElementsByTagName("customerGateway");

        for( int i=0; i<blocks.getLength(); i++ ) {
            Node item = blocks.item(i);
            VPNGateway gateway = toGateway(ctx, item);

            if( gateway != null ) {
                return gateway;
            }
        }
        throw new CloudException("No VPN gateway was created, but no error was reported");
    }

    @Override
    public void deleteVPN(@Nonnull String providerVpnId) throws CloudException, InternalException {
        Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), ELBMethod.DELETE_VPN_GATEWAY);
        EC2Method method;

        parameters.put("VpnGatewayId", providerVpnId);
        method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
            method.invoke();
        }
        catch( EC2Exception e ) {
            logger.error(e.getSummary());
            e.printStackTrace();
            throw new CloudException(e);
        }
    }

    @Override
    public void deleteVPNGateway(@Nonnull String gatewayId) throws CloudException, InternalException {
        Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), ELBMethod.DELETE_CUSTOMER_GATEWAY);
        EC2Method method;

        parameters.put("CustomerGatewayId", gatewayId);
        method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
            method.invoke();
        }
        catch( EC2Exception e ) {
            logger.error(e.getSummary());
            if( logger.isDebugEnabled() ) {
                e.printStackTrace();
            }
            throw new CloudException(e);
        }
    }

    @Override
    public void detachFromVLAN(@Nonnull String providerVpnId, @Nonnull String providerVlanId) throws CloudException, InternalException {
        ProviderContext ctx = provider.getContext();

        if( ctx == null ) {
            throw new CloudException("No context was configured");
        }
        Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), ELBMethod.DETACH_VPN_GATEWAY);
        EC2Method method;

        parameters.put("VpcId", providerVlanId);
        parameters.put("VpnGatewayId", providerVpnId);
        method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
            method.invoke();
        }
        catch( EC2Exception e ) {
            logger.error(e.getSummary());
            e.printStackTrace();
            throw new CloudException(e);
        }
    }

    @Override
    public void disconnectFromGateway(@Nonnull String vpnId, @Nonnull String gatewayId) throws CloudException, InternalException {
        VPNGateway gateway = getGateway(gatewayId);
        VPN vpn = getVPN(vpnId);

        if( gateway == null ) {
            throw new CloudException("No such VPN gateway: " + gatewayId);
        }
        if( vpn == null ) {
            throw new CloudException("No such VPN: " + vpnId);
        }
        String connectionId = null;

        for( VPNConnection c : listConnections(vpnId, null) ) {
            if( gatewayId.equals(c.getProviderGatewayId()) ) {
                connectionId = c.getProviderVpnConnectionId();
                break;
            }
        }
        if( connectionId == null ) {
            logger.warn("Attempt to disconnect a VPN from a gateway when there was no connection in the cloud");
            return;
        }
        ProviderContext ctx = provider.getContext();

        if( ctx == null ) {
            throw new CloudException("No context was configured");
        }
        Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), ELBMethod.DELETE_VPN_CONNECTION);
        EC2Method method;

        parameters.put("VpnConnectionId", connectionId);
        method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
            method.invoke();
        }
        catch( EC2Exception e ) {
            logger.error(e.getSummary());
            e.printStackTrace();
            throw new CloudException(e);
        }
        
    }

    private String getAWSProtocol(VPNProtocol protocol) throws CloudException {
        if( protocol.equals(VPNProtocol.IPSEC1) ) {
            return "ipsec.1";
        }
        throw new CloudException("AWS does not support " + protocol);
    }

    @Override
    public @Nullable VPNGateway getGateway(@Nonnull String gatewayId) throws CloudException, InternalException {
        Iterator<VPNGateway> it = listGateways(gatewayId, null).iterator();
        
        if( it.hasNext() ) {
            return it.next();
        }
        return null;
    }
    
    @Override
    public @Nullable VPN getVPN(@Nonnull String providerVpnId) throws CloudException, InternalException {
        Iterator<VPN> it = listVPNs(providerVpnId).iterator();
        
        return (it.hasNext() ? it.next() : null);
    }

    @Override
    public Requirement getVPNDataCenterConstraint() {
        return Requirement.NONE;
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), ELBMethod.DESCRIBE_CUSTOMER_GATEWAYS);
        EC2Method method;

        method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
            method.invoke();
            return true;
        }
        catch( EC2Exception e ) {
            if( e.getStatus() == HttpServletResponse.SC_UNAUTHORIZED || e.getStatus() == HttpServletResponse.SC_FORBIDDEN ) {
                return false;
            }
            String code = e.getCode();

            if( code != null && (code.equals("SubscriptionCheckFailed") || code.equals("AuthFailure") || code.equals("SignatureDoesNotMatch") || code.equals("UnsupportedOperation") || code.equals("InvalidClientTokenId") || code.equals("OptInRequired")) ) {
                return false;
            }
            logger.error(e.getSummary());
            e.printStackTrace();
            throw new CloudException(e);
        }
    }

    @Override
    public @Nonnull Iterable<VPNConnection> listGatewayConnections(@Nonnull String toGatewayId) throws CloudException, InternalException {
        return listConnections(null, toGatewayId);
    }
    
    private @Nonnull Iterable<VPNConnection> listConnections(@Nullable String vpnId, @Nullable String gatewayId) throws CloudException, InternalException {
        ProviderContext ctx = provider.getContext();

        if( ctx == null ) {
            throw new CloudException("No context was configured");
        }
        Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), ELBMethod.DESCRIBE_VPN_CONNECTIONS);
        EC2Method method;
        NodeList blocks;
        Document doc;

        if( gatewayId != null ) {
            parameters.put("Filter.1.Name", "customer-gateway-id");
            parameters.put("Filter.1.Value.1", gatewayId);
        }
        else if( vpnId != null ) {
            parameters.put("Filter.1.Name", "vpn-gateway-id");
            parameters.put("Filter.1.Value.1", vpnId);
        }
        method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
            doc = method.invoke();
        }
        catch( EC2Exception e ) {
            String code = e.getCode();

            if( code != null ) {
                if( code.startsWith("InvalidCustomer") || code.startsWith("InvalidVpn") ) {
                    return Collections.emptyList();
                }
            }
            logger.error(e.getSummary());
            throw new CloudException(e);
        }
        ArrayList<VPNConnection> list = new ArrayList<VPNConnection>();

        blocks = doc.getElementsByTagName("item");
        for( int i=0; i<blocks.getLength(); i++ ) {
            Node item = blocks.item(i);
            VPNConnection c = toConnection(ctx, item);

            if( c != null ) {
                list.add(c);
            }
        }
        return list;
    }
    
    @Override
    public @Nonnull Iterable<VPNGateway> listGateways() throws CloudException, InternalException {
        return listGateways(null, null);
    }
    
    private @Nonnull Iterable<VPNGateway> listGateways(@Nullable String gatewayId, @Nullable String bgpAsn) throws CloudException, InternalException {
        ProviderContext ctx = provider.getContext();

        if( ctx == null ) {
            throw new CloudException("No context was configured");
        }
        Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), ELBMethod.DESCRIBE_CUSTOMER_GATEWAYS);
        EC2Method method;
        NodeList blocks;
        Document doc;

        if( gatewayId != null ) {
            parameters.put("Filter.1.Name", "customer-gateway-id");
            parameters.put("Filter.1.Value.1", gatewayId);            
        }
        else if( bgpAsn != null ) {
            parameters.put("Filter.1.Name", "bgp-asn");
            parameters.put("Filter.1.Value.1", bgpAsn);
        }
        method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
            doc = method.invoke();
        }
        catch( EC2Exception e ) {
            String code = e.getCode();
            
            if( code != null ) {
                if( code.startsWith("InvalidCustomer") || code.startsWith("InvalidB") ) {
                    return Collections.emptyList();
                }
            }
            logger.error(e.getSummary());
            throw new CloudException(e);
        }
        ArrayList<VPNGateway> list = new ArrayList<VPNGateway>();

        blocks = doc.getElementsByTagName("item");
        for( int i=0; i<blocks.getLength(); i++ ) {
            Node item = blocks.item(i);
            VPNGateway gw = toGateway(ctx, item);

            if( gw != null ) {
                list.add(gw);
            }
        }
        return list;
    }
    
    @Override
    public @Nonnull Iterable<VPNGateway> listGatewaysWithBgpAsn(@Nonnull String bgpAsn) throws CloudException, InternalException {
        return listGateways(null, bgpAsn);
    }

    @Override
    public @Nonnull Iterable<VPNConnection> listVPNConnections(@Nonnull String toVpnId) throws CloudException, InternalException {
        return listConnections(toVpnId, null);
    }

    @Override
    public @Nonnull Iterable<VPNProtocol> listSupportedVPNProtocols() throws CloudException, InternalException {
        return Collections.singletonList(VPNProtocol.IPSEC1);
    }
    
    @Override
    public @Nonnull Iterable<VPN> listVPNs() throws CloudException, InternalException {
        return listVPNs(null);
    }

    private @Nonnull Iterable<VPN> listVPNs(@Nullable String vpnId) throws CloudException, InternalException {
        ProviderContext ctx = provider.getContext();

        if( ctx == null ) {
            throw new CloudException("No context was configured");
        }
        Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), ELBMethod.DESCRIBE_VPN_GATEWAYS);
        EC2Method method;
        NodeList blocks;
        Document doc;

        if( vpnId != null ) {
            parameters.put("VpnGatewayId.1", vpnId);
        }
        method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
            doc = method.invoke();
        }
        catch( EC2Exception e ) {
            String code = e.getCode();

            if( code != null ) {
                if( code.startsWith("InvalidVpn") ) {
                    return Collections.emptyList();
                }
            }
            logger.error(e.getSummary());
            throw new CloudException(e);
        }
        ArrayList<VPN> list = new ArrayList<VPN>();

        blocks = doc.getElementsByTagName("item");
        for( int i=0; i<blocks.getLength(); i++ ) {
            Node item = blocks.item(i);
            VPN vpn = toVPN(ctx, item);

            if( vpn != null ) {
                list.add(vpn);
            }
        }
        return list;
    }

    @Override
    public @Nonnull String[] mapServiceAction(@Nonnull ServiceAction action) {
        if( action.equals(VPNSupport.ANY) ) {
            return new String[] { EC2Method.EC2_PREFIX + "*" };
        }
        else if( action.equals(VPNSupport.ATTACH) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.ATTACH_VPN_GATEWAY };
        }
        else if( action.equals(VPNSupport.CREATE_GATEWAY) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.CREATE_CUSTOMER_GATEWAY };
        }
        else if( action.equals(VPNSupport.CREATE_VPN) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.CREATE_VPN_GATEWAY };
        }
        else if( action.equals(VPNSupport.GET_GATEWAY) || action.equals(VPNSupport.LIST_GATEWAY) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.DESCRIBE_CUSTOMER_GATEWAYS, EC2Method.EC2_PREFIX + EC2Method.DESCRIBE_VPN_CONNECTIONS };
        }
        else if( action.equals(VPNSupport.GET_VPN) || action.equals(VPNSupport.LIST_VPN) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.DESCRIBE_VPN_GATEWAYS, EC2Method.EC2_PREFIX + EC2Method.DESCRIBE_VPN_CONNECTIONS };
        }
        else if( action.equals(VPNSupport.REMOVE_GATEWAY) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.DELETE_CUSTOMER_GATEWAY };
        }
        else if( action.equals(VPNSupport.REMOVE_VPN) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.DELETE_VPN_GATEWAY };
        }
        else if( action.equals(VPNSupport.DETACH) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.DETACH_VPN_GATEWAY };
        }
        return new String[0];
    }
    
    private @Nullable VPNConnection toConnection(@SuppressWarnings("UnusedParameters") @Nonnull ProviderContext ctx, @Nullable Node node) throws CloudException, InternalException {
        if( node == null ) {
            return null;
        }

        NodeList attributes = node.getChildNodes();
        VPNConnection connection = new VPNConnection();

        connection.setCurrentState(VPNConnectionState.PENDING);
        for( int i=0; i<attributes.getLength(); i++ ) {
            Node attr = attributes.item(i);
            String nodeName = attr.getNodeName();

            if( nodeName.equalsIgnoreCase("vpnConnectionId") && attr.hasChildNodes() ) {
                connection.setProviderVpnConnectionId(attr.getFirstChild().getNodeValue().trim());
            }
            else if( nodeName.equalsIgnoreCase("customerGatewayId") && attr.hasChildNodes() ) {
                connection.setProviderGatewayId(attr.getFirstChild().getNodeValue().trim());
            }
            else if( nodeName.equalsIgnoreCase("vpnGatewayId") && attr.hasChildNodes() ) {
                connection.setProviderVpnId(attr.getFirstChild().getNodeValue().trim());
            }
            else if( nodeName.equalsIgnoreCase("customerGatewayConfiguration") && attr.hasChildNodes() ) {
                connection.setConfigurationXml(attr.getFirstChild().getNodeValue().trim());
            }
            else if( nodeName.equalsIgnoreCase("state") && attr.hasChildNodes() ) {
                String state = attr.getFirstChild().getNodeValue().trim();

                if( state.equalsIgnoreCase("available") ) {
                    connection.setCurrentState(VPNConnectionState.AVAILABLE);
                }
                else if( state.equalsIgnoreCase("deleting") ) {
                    connection.setCurrentState(VPNConnectionState.DELETING);
                }
                else if( state.equalsIgnoreCase("deleted") ) {
                    connection.setCurrentState(VPNConnectionState.DELETED);
                }
                else if( state.equalsIgnoreCase("pending") ) {
                    connection.setCurrentState(VPNConnectionState.PENDING);
                }
                else {
                    logger.warn("DEBUG: Unknown VPN connection state: " + state);
                }
            }
            else if( nodeName.equalsIgnoreCase("type") && attr.hasChildNodes() ) {
                String t = attr.getFirstChild().getNodeValue().trim();

                if( t.equalsIgnoreCase("ipsec.1") ) {
                    connection.setProtocol(VPNProtocol.IPSEC1);
                }
                else if( t.equalsIgnoreCase("openvpn") ) {
                    connection.setProtocol(VPNProtocol.OPEN_VPN);
                }
                else {
                    logger.warn("DEBUG: Unknown VPN connection type: " + t);
                    connection.setProtocol(VPNProtocol.IPSEC1);
                }
            }
        }
        if( connection.getProviderVpnConnectionId() == null ) {
            return null;
        }
        return connection;
    }

    private @Nullable VPNGateway toGateway(@Nonnull ProviderContext ctx, @Nullable Node node) throws CloudException, InternalException {
        if( node == null ) {
            return null;
        }

        NodeList attributes = node.getChildNodes();
        VPNGateway gateway = new VPNGateway();
        
        gateway.setProviderOwnerId(ctx.getAccountNumber());
        gateway.setProviderRegionId(ctx.getRegionId());
        gateway.setCurrentState(VPNGatewayState.PENDING);
        for( int i=0; i<attributes.getLength(); i++ ) {
            Node attr = attributes.item(i);
            String nodeName = attr.getNodeName();
            
            if( nodeName.equalsIgnoreCase("customerGatewayId") && attr.hasChildNodes() ) {
                gateway.setProviderVpnGatewayId(attr.getFirstChild().getNodeValue().trim());
            }
            else if( nodeName.equalsIgnoreCase("state") && attr.hasChildNodes() ) {
                String state = attr.getFirstChild().getNodeValue().trim();
                
                if( state.equalsIgnoreCase("available") ) {
                    gateway.setCurrentState(VPNGatewayState.AVAILABLE);
                }
                else if( state.equalsIgnoreCase("deleting") ) {
                    gateway.setCurrentState(VPNGatewayState.DELETING);
                }
                else if( state.equalsIgnoreCase("deleted") ) {
                    gateway.setCurrentState(VPNGatewayState.DELETED);
                }
                else if( state.equalsIgnoreCase("pending") ) {
                    gateway.setCurrentState(VPNGatewayState.PENDING);
                }
                else {
                    logger.warn("DEBUG: Unknown VPN gateway state: " + state);
                }
            }
            else if( nodeName.equalsIgnoreCase("type") && attr.hasChildNodes() ) {
                String t = attr.getFirstChild().getNodeValue().trim();
                
                if( t.equalsIgnoreCase("ipsec.1") ) {
                    gateway.setProtocol(VPNProtocol.IPSEC1);
                }
                else if( t.equalsIgnoreCase("openvpn") ) {
                    gateway.setProtocol(VPNProtocol.OPEN_VPN);
                }
                else {
                    logger.warn("DEBUG: Unknown VPN gateway type: " + t);
                    gateway.setProtocol(VPNProtocol.IPSEC1);
                }
            }
            else if( nodeName.equalsIgnoreCase("ipAddress") && attr.hasChildNodes() ) {
                gateway.setEndpoint(attr.getFirstChild().getNodeValue().trim());
            }
            else if( nodeName.equalsIgnoreCase("bgpAsn") && attr.hasChildNodes() ) {
                gateway.setBgpAsn(attr.getFirstChild().getNodeValue().trim());
            }
            else if( nodeName.equalsIgnoreCase("tagSet") && attr.hasChildNodes() ) {
                provider.setTags(attr, gateway);
            }
        }
        if( gateway.getProviderVpnGatewayId() == null ) {
            return null;
        }
        if( gateway.getName() == null ) {
            gateway.setName(gateway.getProviderVpnGatewayId() + " [" + gateway.getEndpoint() + "]");
        }
        if( gateway.getDescription() == null ) {
            gateway.setDescription(gateway.getName());
        }
        return gateway;
    }
    
    private @Nullable VPN toVPN(@Nonnull ProviderContext ctx, @Nullable Node node) throws CloudException, InternalException {
        if( node == null ) {
            return null;
        }
        
        NodeList attributes = node.getChildNodes();
        String name = null, description = null;
        VPN vpn = new VPN();

        vpn.setCurrentState(VPNState.PENDING);
        vpn.setProviderRegionId(ctx.getRegionId());
        for( int i=0; i<attributes.getLength(); i++ ) {
            Node attr = attributes.item(i);
            String nodeName = attr.getNodeName();
            
            if( nodeName.equalsIgnoreCase("vpnGatewayId") && attr.hasChildNodes() ) {
                vpn.setProviderVpnId(attr.getFirstChild().getNodeValue().trim());
            }
            else if( nodeName.equalsIgnoreCase("state") ) {
                String state = attr.getFirstChild().getNodeValue().trim();

                if( state.equalsIgnoreCase("available") ) {
                    vpn.setCurrentState(VPNState.AVAILABLE);
                }
                else if( state.equalsIgnoreCase("deleting") ) {
                    vpn.setCurrentState(VPNState.DELETING);
                }
                else if( state.equalsIgnoreCase("deleted") ) {
                    vpn.setCurrentState(VPNState.DELETED);
                }
                else if( state.equalsIgnoreCase("pending") ) {
                    vpn.setCurrentState(VPNState.PENDING);
                }
                else {
                    logger.warn("DEBUG: Unknown VPN state: " + state);
                }
            }
            else if( nodeName.equalsIgnoreCase("type") && attr.hasChildNodes() ) {
                String t = attr.getFirstChild().getNodeValue().trim();

                if( t.equalsIgnoreCase("ipsec.1") ) {
                    vpn.setProtocol(VPNProtocol.IPSEC1);
                }
                else if( t.equalsIgnoreCase("openvpn") ) {
                    vpn.setProtocol(VPNProtocol.OPEN_VPN);
                }
                else {
                    logger.warn("DEBUG: Unknown VPN gateway type: " + t);
                    vpn.setProtocol(VPNProtocol.IPSEC1);
                }
            }
            else if( nodeName.equalsIgnoreCase("attachments") && attr.hasChildNodes() ) {
                TreeSet<String> vlans = new TreeSet<String>();
                NodeList list = attr.getChildNodes();
                
                for( int j=0; j<list.getLength(); j++ ) {
                    Node att = list.item(j);
                    
                    if( att.getNodeName().equalsIgnoreCase("item") && att.hasChildNodes() ) {
                        NodeList aaList = attr.getChildNodes();
                        String id = null;
                        
                        for( int k=0; k<aaList.getLength(); k++ ) {
                            Node aa = aaList.item(k);
                            
                            if( aa.getNodeName().equalsIgnoreCase("vpcId") && aa.hasChildNodes() ) {
                                id = aa.getFirstChild().getNodeValue().trim();
                                break;
                            }
                        }
                        if( id != null ) {
                            vlans.add(id);
                        }
                    }
                }
                vpn.setProviderVlanIds(vlans.toArray(new String[vlans.size()]));
            }
            else if( nodeName.equalsIgnoreCase("tagSet") && attr.hasChildNodes() ) {
                provider.setTags(attr, vpn);
                if( vpn.getTags().get("name") != null ) {
                    name =  vpn.getTags().get("name");
                }
                if( vpn.getTags().get("description") != null ) {
                    description =  vpn.getTags().get("description");
                }
            }
        }
        if( vpn.getProviderVpnId() == null ) {
            return null;
        }
        if( vpn.getName() == null ) {
            vpn.setName(name == null ? vpn.getProviderVpnId() : name);
        }
        if( vpn.getDescription() == null ) {
            vpn.setDescription(description == null ? vpn.getName() : description);
        }
        return vpn;
    }
}
