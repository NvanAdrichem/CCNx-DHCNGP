//Dynamic Host Configuration and Name Generation Protocol (DHCNGP)
//
//Copyright (C) 2012, Delft University of Technology, Faculty of Electrical Engineering, Mathematics and Computer Science, Network Architectures and Services, Niels van Adrichem
//
//    This file is part of DHCNGP.
//
//    DHCNGP is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License version 3 as published by
//    the Free Software Foundation.
//
//    DHCNGP is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with DHCNGP.  If not, see <http://www.gnu.org/licenses/>.


import java.io.*;
import java.net.*;
import java.util.*;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.CCNNetworkManager.NetworkProtocol;

import org.ccnx.ccn.profiles.ccnd.CCNDaemonException;
import org.ccnx.ccn.profiles.ccnd.FaceManager;
import org.ccnx.ccn.profiles.ccnd.PrefixRegistrationManager;

import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;





public class DHCNGP implements CCNInterestHandler, CCNContentHandler {

	/**
	 * @param args
	*/
	
	public class NoMatchingFaceException extends Exception{

		/**
		 * 
		 */
		private static final long serialVersionUID = 7949814977418667538L;}
	
	private static final long CYCLE_TIME = 10000;
	private static final NetworkProtocol NETW_PROTO = NetworkProtocol.UDP;
	private static final int NETW_PORT = 9695;
	private static final int NETW_DISC_PORT = 49695;
	private static final String NETW_DISC_NAME = "ccnx:/local/dhcngp"; 
	
	
	private CCNHandle controlCCNHandle;
	private FaceManager myFaceManager;
	private PrefixRegistrationManager myPrefixRegistrationManager;
	
	private String hostID;
	private String hostName;
	
	private RuleManager myRuleManager;
	
	public static void main(String[] args) {
		// TODO Auto-generated method stub
		
		
		try {
			System.out.println("Welcome to the Dynamic Host Configuration and Name Generation daemon.");
			DHCNGP client;
			client = new DHCNGP();
			client.run();
		} catch (MalformedContentNameStringException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (CCNDaemonException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ConfigurationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
		
		
	
		
		
	}
	
	
	private void loadHostName() throws UnknownHostException
	{
		java.net.InetAddress addr;
		
		addr = java.net.InetAddress.getLocalHost();
			
		hostName = addr.getHostName();
		System.out.println("HostName = " + hostName);			
	}
	
	private void loadID() throws UnknownHostException
	{
		java.net.InetAddress addr;
		
		addr = java.net.InetAddress.getLocalHost();
			
		hostID = addr.getHostName();
		System.out.println("HostID = " + hostID);			
	}
	
	private void loadRules()
	{
		myRuleManager = new RuleManager();
		
		ArrayList<Rule> configRules = new ArrayList<Rule>();
		try {
			Properties confFile = new Properties();
			FileInputStream confStream = new FileInputStream("config.properties");
			confFile.load(confStream);

			
			//iterates through all entrypoints.
			String entrypoint;
			for(int i = 0; (entrypoint  = confFile.getProperty("entry."+i)) != null; i++)
			{

				
				Rule tRule = new Rule( ContentName.fromURI(entrypoint) );
				if( confFile.getProperty("entry."+i+".cost") != null)
					tRule.cost = new Integer( confFile.getProperty("entry."+i+".cost") );
				
				if( confFile.getProperty("entry."+i+".aggregate") != null )
				{
					tRule.aggregate = new Boolean( confFile.getProperty("entry."+i+".aggregate") );
					tRule.aggregatedName = ContentName.fromURI(entrypoint);
				}
				configRules.add(tRule);
				System.out.println("Static entrypoint " + entrypoint + " added to proposed list");
			}
			
			myRuleManager.considerRules(null, configRules.toArray(new Rule[configRules.size()]));
			
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public DHCNGP() throws MalformedContentNameStringException, CCNDaemonException, IOException, ConfigurationException 
	{
		loadID();
		loadHostName();
		
		
		loadRules();
		//ListInetAddresses();
		connectCCNx();
		createMulticastFaces();
	}
	public HashMap<ContentName, Rule> aggrTable;
	private CCNHandle trafficCCNHandle;
	public synchronized void run() throws InterruptedException, SocketException
	{
		aggrTable = myRuleManager.calculateRules();
		
		System.out.println("Sending discovery message to local subnets");
		SendDiscover();		
		
		wait(CYCLE_TIME);
		System.out.println("Done waiting for Offer-responses, calculating preferred forwarding table");
		aggrTable = myRuleManager.calculateRules();
	
		System.out.println("The following aggregationTable has formed. The usage of dynamically found rules is requested.");
		for(Rule tRule : aggrTable.values())
		{
			System.out.println("\t"+tRule);
		}
		
		requestRoutes(aggrTable);
	}
		
	public void requestRoutes(HashMap<ContentName, Rule> aggrTable) throws SocketException {
		// TODO Auto-generated method stub
		HashMap<String, ArrayList<Rule> > routesBySource = new HashMap<String, ArrayList<Rule> >();
		
		for(Rule tRule : aggrTable.values())
		{
			if(tRule.source == null)
			{
				activateRule(tRule);
				continue;
			}
			
			if(routesBySource.get(tRule.source) == null)
				routesBySource.put(tRule.source, new ArrayList<Rule>() );
			
			ArrayList<Rule> list = routesBySource.get(tRule.source);
			list.add(tRule);
	
		}
		
		for(String key : routesBySource.keySet())
		{
			
			Message msg = new Message(Message.MessageType.Request);
			msg.clientID = hostID;
			msg.serverID = key;
			msg.faces = listFaces();
			
			ArrayList<Rule> tRules = routesBySource.get(key);
			msg.rules = tRules.toArray(new Rule[tRules.size()]);
			try {
				Send(hostID, key, msg);
			} catch (MalformedContentNameStringException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
				
		}
	}


	private void activateRule(Rule tRule) {
		// TODO Auto-generated method stub
		tRule.active = true;
	}


	private void SendDiscover() {
		try {
			System.out.println("A discovery packet will be send.");
			Message discMsg = new Message(Message.MessageType.Discover);
			discMsg.clientID = hostID;
			discMsg.clientHostname = hostName;
			discMsg.faces = listFaces();
			
			Send(hostID, null, discMsg);
			
		} catch (MalformedContentNameStringException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}




	private Face[] listFaces() throws SocketException {
		// TODO Auto-generated method stub
		InterfaceAddress intAddrs[] = ListInterfaces();
		ArrayList<Face> faces = new ArrayList<Face>();
		for (InterfaceAddress addrs : intAddrs)
		{
			Face face = new Face(NETW_PROTO, addrs.getAddress(), NETW_PORT);
			faces.add(face);
		}
		
		return faces.toArray(new Face[faces.size()]);
	}




	private void createMulticastFaces() {
		// TODO Auto-generated method stub
		System.out.println("Creating multicast faces.");
		try {
			
			for(InterfaceAddress interfaceaddress : ListInterfaces())
			{
				//System.out.println("\tFor 224.0.23."+ (0x000000FF & interfaceaddress.getAddress().getAddress()[2]));
				//int faceID = myFaceManager.createFace(NetworkProtocol.UDP, interfaceaddress.getBroadcast().getHostAddress(), 59695);
				int faceID = myFaceManager.createFace(NetworkProtocol.UDP, "224.0.23.170", NETW_DISC_PORT, interfaceaddress.getAddress().getHostAddress(), 1, null);
				//int faceID = myFaceManager.createFace(NetworkProtocol.UDP, "224.0.23."+(0x000000FF & interfaceaddress.getAddress().getAddress()[2]), NETW_DISC_PORT, interfaceaddress.getAddress().getHostAddress(), 1, null);
				//System.out.println(interfaceaddress.getAddress().getHostAddress());
				
				//System.out.println("FaceId " + faceID);
				
				try {
					myPrefixRegistrationManager.registerPrefix(ContentName.fromURI(NETW_DISC_NAME+"/"+hostID), faceID, null);
					
				} catch (MalformedContentNameStringException e) {
					
					//it won't be, since the ContentName is static
					e.printStackTrace();
				}
				
			}
		} catch (SocketException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (CCNDaemonException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}




	private void connectCCNx() throws MalformedContentNameStringException, IOException, CCNDaemonException, ConfigurationException {
		// TODO Auto-generated method stub
		controlCCNHandle = CCNHandle.getHandle();
		trafficCCNHandle = CCNHandle.open();
		trafficCCNHandle.registerFilter( ContentName.fromURI(NETW_DISC_NAME), this);
		
		myFaceManager = new FaceManager(controlCCNHandle);
		//int faceID = myFaceManager.createFace(NETW_PROTO.UDP, "127.0.0.1" , NETW_DISC_PORT);
		
		myPrefixRegistrationManager = new PrefixRegistrationManager(controlCCNHandle);
		//myPrefixRegistrationManager.registerPrefix(ContentName.fromURI(NETW_DISC_NAME), faceID, null);
		
	}




	private InterfaceAddress[] ListInterfaces() throws SocketException
	{
		
		ArrayList<InterfaceAddress> interfaces = new ArrayList<InterfaceAddress>();
		
		 Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
	        for (NetworkInterface netint : Collections.list(nets))
	        {
	        	//System.out.printf("Display name: %s\n", netint.getDisplayName());
	        	//System.out.printf("Name: %s\n", netint.getName());
	        	
	            for (InterfaceAddress interfaceAddress : netint.getInterfaceAddresses()) {
	            	InetAddress inetAddress = interfaceAddress.getAddress();
	            	
	            	if(!inetAddress.isMulticastAddress() && !inetAddress.isLinkLocalAddress() && !inetAddress.isLoopbackAddress() && !interfaceAddress.getBroadcast().getHostAddress().equals( "172.19.255.255" ))
	            	{
	            		//System.out.println(interfaceAddress.getBroadcast().getHostAddress());
	            		interfaces.add(interfaceAddress);
	            	}
	            	//System.out.printf("InetAddress: %s\n", inetAddress);
	            }
	            //System.out.printf("\n");
	        }
	    
	     return interfaces.toArray( new InterfaceAddress[interfaces.size()] );
	}

	public void Send(String from, String to, Message msg) throws MalformedContentNameStringException, IOException
	{
		//ContentName cn = ContentName.fromNative(ContentName.fromURI(NETW_DISC_NAME+"/"+hostID), Serializer.serialize(discMsg));
		if(to == null)
			to = "_null";
		ContentName cn = ContentName.fromURI(NETW_DISC_NAME+"/"+from+"/"+to);		
		//System.out.println("Name " + cn);
		cn = ContentName.fromNative(cn, Serializer.serialize(msg));
		
		Interest intrst = new Interest(cn);
		System.out.println("Sending " + msg.msgType + " message from " + from + " to " + to);
		trafficCCNHandle.expressInterest(intrst, this);
		//initially, we only want the Interest to be send once, therefore we now cancel the Interest.
		trafficCCNHandle.cancelInterest(intrst, this);
	}
	
	@Override
	public synchronized boolean handleInterest(Interest interest) {
		//System.out.println("Receiving an Interest with name: " + interest.name());
		
		String from = new String(interest.name().component(2));

		String to = new String(interest.name().component(3));
		
		System.out.println( "Receiving a message from " + from + " to " + to);
		
		if(!to.equals("_null") && !to.equals(hostID))
		{
			System.out.println("Ignoring message, other recipient");
			return true;
		}
		
		try {
			Message incMsg = ( (Message) Serializer.deserialize( interest.name().lastComponent()));
			System.out.println( "Processing the " + incMsg.msgType + " message");
			switch(incMsg.msgType)
			{
			case Discover:
				//do the things necessary for discovery
								
				//should check if its interfaceaddresses match one of our network...
				
				//if there is already a registration for that hostname to another hostid, generate a new one.
				
				Message offMsg = new Message(Message.MessageType.Offer);
				offMsg.clientID = incMsg.clientID;
				offMsg.serverID = hostID;
				offMsg.clientHostname = incMsg.clientHostname;
				
				ArrayList<Rule> tRules = new ArrayList<Rule>();
				for(Rule tRule : aggrTable.values())
				{
					tRules.add( new Rule(tRule, hostID, incMsg.clientHostname, hostID) );
				}
				
				offMsg.rules = tRules.toArray(new Rule[tRules.size()]);
				
				try {
					Send(hostID, offMsg.clientID, offMsg);
				} catch (MalformedContentNameStringException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				break;
			case Offer:
				//System.out.println("It is an offering message");
				
				//Consider the rules...
				for(Rule tRule: incMsg.rules)
				{
					tRule.cost += 10;
				}
				myRuleManager.considerRules(incMsg.serverID, incMsg.rules);
				
				//set some flag that we need recalculation of the rules.
				
				break;
				
			case Request:
								
				//We should check if the rules fit our aggregationTable (the requested rules should be a subset of the rules we sent during the discovery and offer process.
				//For now, we bluntly add routes and acknowledge.
				
				//determine the face we wish to use.
				try {
					Face tFace = determineMatchingFace(incMsg.faces);
					System.out.println("\tCreating face to host " + incMsg.clientID + " on " +tFace.host.getHostAddress() );				
					
					int intFace = myFaceManager.createFace(tFace.protocol, tFace.host.getHostAddress(), tFace.port);
					for(Rule tRule: incMsg.rules)
						if(tRule.aggregate)
						{
							System.out.println("\t\tAdding forwarding rule for dynamically generated name " + tRule.aggregatedName);
							myPrefixRegistrationManager.registerPrefix(tRule.aggregatedName, intFace, null);
						}
					
					Message outMsg = new Message(Message.MessageType.Acknowledgement);
					outMsg.clientID = incMsg.clientID;
					outMsg.serverID = hostID;
					outMsg.faces = listFaces();
					outMsg.rules = incMsg.rules;
					
					try {
						Send(hostID, incMsg.clientID, outMsg);
					} catch (MalformedContentNameStringException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					
				} catch (NoMatchingFaceException e1) {
					// TODO Auto-generated catch block
					//e1.printStackTrace();
					
					//we should send a nack that the request is not honoured.
				} catch (CCNDaemonException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				break;
				
			case Acknowledgement:
				
				try {
					
					try {
						Face tFace = determineMatchingFace(incMsg.faces);
						System.out.println("\tCreating face to host " + incMsg.serverID + " on "+tFace.host.getHostAddress());
						int faceId = myFaceManager.createFace(tFace.protocol, tFace.host.getHostAddress(), tFace.port);
						
						for(Rule tRule : incMsg.rules)
						{
							System.out.println("\t\tAdding forwarding rule for entrypoint " + tRule.entrypoint);
							myPrefixRegistrationManager.registerPrefix(tRule.entrypoint, faceId, null);
						}
						
					} catch (CCNDaemonException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					
								
					
				
					
				} catch (NoMatchingFaceException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				break;
				
			}
			
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return true;
	}
	
	private static boolean matchIPv4LocalNetwork(InetAddress ip1, InetAddress ip2, int netPrefix) {
			//System.out.println("Prefix comparing "+ip1.getHostAddress()+ " with "+ip2.getHostAddress()+" on prefix "+netPrefix);
	  
		   int calcIP1 = 0;
		   int calcIP2 = 0;
		   
		   byte [] givenIP1 = ip1.getAddress();
		   byte [] givenIP2 = ip2.getAddress();
		   
		   
		   int mult = 256*256*256;
		   for(int i = 0; i < 4; i++)
		   {
			   calcIP1 += mult * givenIP1[i];
			   calcIP2 += mult * givenIP2[i];
			   mult /= 256;
		   }
		   
		   calcIP1 = calcIP1 >>> (32-netPrefix);
		   calcIP2 = calcIP2 >>> (32-netPrefix);
		   

	  // System.out.println(" which is "+(calcIP1 == calcIP2));
	   return calcIP1 == calcIP2;
	   
	}

	private Face determineMatchingFace(Face[] faces) throws NoMatchingFaceException {
		// TODO Auto-generated method stub
		InterfaceAddress[] ifAddrs;
		try {
			ifAddrs = ListInterfaces();
		
			for(Face face : faces)
			{
				for(InterfaceAddress ifAddr : ifAddrs)
				{
					
					if(matchIPv4LocalNetwork(face.host, ifAddr.getAddress(), ifAddr.getNetworkPrefixLength()))
						return face;
				}
			}
		} catch (SocketException e) {
			// TODO Auto-generated catch block
			throw new NoMatchingFaceException();
		}
		
		throw new NoMatchingFaceException();
		
		
	}


	@Override
	public Interest handleContent(ContentObject data, Interest interest) {
		//We are not supposed to receive any content, since we encapsulate everything within Interests.
		//Still, since we send out Interests we should provide a callback routine for possible returning Content.
		return null;
	}

}
