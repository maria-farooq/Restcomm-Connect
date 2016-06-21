/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */
package org.mobicents.servlet.restcomm.telephony.ua;

import akka.actor.ActorRef;
import akka.actor.ReceiveTimeout;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import com.telestax.servlet.MonitoringService;
import org.apache.commons.configuration.Configuration;
import org.joda.time.DateTime;
import org.mobicents.servlet.restcomm.configuration.RestcommConfiguration;
import org.mobicents.servlet.restcomm.dao.ClientsDao;
import org.mobicents.servlet.restcomm.dao.DaoManager;
import org.mobicents.servlet.restcomm.dao.RegistrationsDao;
import org.mobicents.servlet.restcomm.entities.Client;
import org.mobicents.servlet.restcomm.entities.Registration;
import org.mobicents.servlet.restcomm.entities.Sid;
import org.mobicents.servlet.restcomm.telephony.UserRegistration;
import org.mobicents.servlet.restcomm.util.DigestAuthentication;
import scala.concurrent.duration.Duration;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipURI;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.lang.Integer.parseInt;
import static javax.servlet.sip.SipServlet.OUTBOUND_INTERFACES;
import static javax.servlet.sip.SipServletResponse.SC_OK;
import static javax.servlet.sip.SipServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED;
import static org.mobicents.servlet.restcomm.util.HexadecimalUtils.toHex;

/**
 * @author quintana.thomas@gmail.com (Thomas Quintana)
 * @author jean.deruelle@telestax.com
 */
public final class UserAgentManager extends UntypedActor {
    private final LoggingAdapter logger = Logging.getLogger(getContext().system(), this);
    private boolean authenticateUsers = true;
    private final SipFactory factory;
    private final DaoManager storage;
    private final ServletContext servletContext;
    private ActorRef monitoringService;
    private final int pingInterval;

    public UserAgentManager(final Configuration configuration, final SipFactory factory, final DaoManager storage,
            final ServletContext servletContext) {
        super();
        // this.configuration = configuration;
        this.servletContext = servletContext;
        monitoringService = (ActorRef) servletContext.getAttribute(MonitoringService.class.getName());
        final Configuration runtime = configuration.subset("runtime-settings");
        this.authenticateUsers = runtime.getBoolean("authenticate");
        this.factory = factory;
        this.storage = storage;
        pingInterval = runtime.getInt("ping-interval", 60);
        getContext().setReceiveTimeout(Duration.create(pingInterval, TimeUnit.SECONDS));
        logger.info("About to run firstTimeCleanup()");
        firstTimeCleanup();
    }

    private void firstTimeCleanup() {
        if (logger.isInfoEnabled())
            logger.info("Initial registration cleanup. Will check existing registrations in DB and cleanup appropriately");
        final RegistrationsDao registrations = storage.getRegistrationsDao();
        List<Registration> results = registrations.getRegistrations();
        for (final Registration result : results) {
            if (result.isWebRTC()) {
                //If this is a WebRTC registration remove it since after restart the websocket connection is gone
                if (logger.isInfoEnabled())
                    logger.info("Will remove WebRTC client: "+result.getLocation());
                registrations.removeRegistration(result);
                monitoringService.tell(new UserRegistration(result.getUserName(), result.getLocation(), false), self());
            } else {
                final DateTime expires = result.getDateExpires();
                if (expires.isBeforeNow() || expires.isEqualNow()) {
                    if (logger.isInfoEnabled()) {
                        logger.info("Registration: " + result.getLocation() + " expired and will be removed now");
                    }
                    registrations.removeRegistration(result);
                    monitoringService.tell(new UserRegistration(result.getUserName(), result.getLocation(), false), self());
                    return;
                }
                final DateTime updated = result.getDateUpdated();
                Long pingIntervalMillis = new Long(pingInterval * 1000 * 3);
                if ((DateTime.now().getMillis() - updated.getMillis()) > pingIntervalMillis) {
                    //Last time this registration updated was older than (pingInterval * 3), looks like it doesn't respond to OPTIONS
                    if (logger.isInfoEnabled()) {
                        logger.info("Registration: " + result.getLocation() + " didn't respond to OPTIONS and will be removed now");
                    }
                    registrations.removeRegistration(result);
                    monitoringService.tell(new UserRegistration(result.getUserName(), result.getLocation(), false), self());
                }
            }
        }
        results = registrations.getRegistrations();
        if (logger.isInfoEnabled())
            logger.info("Initial registration cleanup finished, starting Restcomm with "+results.size()+" registrations");
    }

    private void clean() {
        final RegistrationsDao registrations = storage.getRegistrationsDao();
        final List<Registration> results = registrations.getRegistrations();
        for (final Registration result : results) {
            final DateTime expires = result.getDateExpires();
            if (expires.isBeforeNow() || expires.isEqualNow()) {
                if(logger.isInfoEnabled()) {
                    logger.info("Registration: "+result.getAddressOfRecord()+" expired and will be removed now");
                }
                registrations.removeRegistration(result);
                monitoringService.tell(new UserRegistration(result.getUserName(), result.getLocation(), false), self());
                return;
            }
            final DateTime updated = result.getDateUpdated();
            Long pingIntervalMillis = new Long(pingInterval * 1000 * 3);
            if ( (DateTime.now().getMillis() - updated.getMillis()) >  pingIntervalMillis) {
                //Last time this registration updated was older than (pingInterval * 3), looks like it doesn't respond to OPTIONS
                if(logger.isInfoEnabled()) {
                    logger.info("Registration: "+result.getAddressOfRecord()+" didn't respond to OPTIONS and will be removed now");
                }
                registrations.removeRegistration(result);
                monitoringService.tell(new UserRegistration(result.getUserName(), result.getLocation(), false), self());
            }
        }
    }

    private String header(final String nonce, final String realm, final String scheme) {
        final StringBuilder buffer = new StringBuilder();
        buffer.append(scheme).append(" ");
        buffer.append("realm=\"").append(realm).append("\", ");
        buffer.append("nonce=\"").append(nonce).append("\"");
        return buffer.toString();
    }

    private void authenticate(final Object message) throws IOException {
        final SipServletRequest request = (SipServletRequest) message;
        final SipServletResponse response = request.createResponse(SC_PROXY_AUTHENTICATION_REQUIRED);
        final String nonce = nonce();
        final SipURI uri = (SipURI) request.getTo().getURI();
        final String realm = uri.getHost();
        final String header = header(nonce, realm, "Digest");
        response.addHeader("Proxy-Authenticate", header);
        response.send();
    }

    private void keepAlive() throws Exception {
        final RegistrationsDao registrations = storage.getRegistrationsDao();
        final List<Registration> results = registrations.getRegistrations();
        for (final Registration result : results) {
            final String to = result.getLocation();
            ping(to);
        }
    }

    private String nonce() {
        final byte[] uuid = UUID.randomUUID().toString().getBytes();
        final char[] hex = toHex(uuid);
        return new String(hex).substring(0, 31);
    }

    @Override
    public void onReceive(final Object message) throws Exception {
        if (message instanceof ReceiveTimeout) {
            clean();
            keepAlive();
        } else if (message instanceof SipServletRequest) {
            final SipServletRequest request = (SipServletRequest) message;
            final String method = request.getMethod();
            if ("REGISTER".equalsIgnoreCase(method)) {
                if(authenticateUsers) { // https://github.com/Mobicents/RestComm/issues/29 Allow disabling of SIP authentication
                    final String authorization = request.getHeader("Proxy-Authorization");
                    if (authorization != null) {
                      if (permitted(authorization, method)) {
                          register(message);
                      } else {
                          SipServletResponse response = ((SipServletRequest) message).createResponse(javax.servlet.sip.SipServletResponse.SC_FORBIDDEN); //Issue #935, Send 403 FORBIDDEN instead of issuing 407 again and again
                          response.send();
                      }
                    } else {
                        authenticate(message);
                    }
                } else {
                    register(message);
                }
            }
        } else if (message instanceof SipServletResponse) {
            SipServletResponse response = (SipServletResponse) message;
            if (response.getStatus()>400 && response.getMethod().equalsIgnoreCase("OPTIONS")) {
                removeRegistration(response);
            } else {
                pong(message);
            }
        }
    }

    private void removeRegistration(final SipServletMessage sipServletMessage) {
        String user = ((SipURI)sipServletMessage.getTo().getURI()).getUser();
        String host = ((SipURI)sipServletMessage.getTo().getURI()).getHost();
        String port = String.valueOf(((SipURI)sipServletMessage.getTo().getURI()).getPort());
        String transport = ((SipURI) sipServletMessage.getTo().getURI()).getTransportParam();
        if(logger.isDebugEnabled()) {
            logger.debug("Error response for the OPTIONS to: "+sipServletMessage.getFrom().toString()+" will remove registration");
        }
        final RegistrationsDao regDao = storage.getRegistrationsDao();
        List<Registration> registrations = regDao.getRegistrations(user);
        if (registrations != null) {
            Iterator<Registration> iter = registrations.iterator();
            SipURI regLocation = null;
            while (iter.hasNext()) {
                Registration reg = iter.next();
                try {
                    regLocation = (SipURI) factory.createURI(reg.getLocation());
                } catch (ServletParseException e) {}

                if (regLocation != null && (reg.getAddressOfRecord().equalsIgnoreCase(regLocation.toString()) || reg.getLocation().equalsIgnoreCase(regLocation.toString()))) {
                    if(logger.isInfoEnabled()) {
                        logger.info("Registration: " + reg.getLocation() + " failed to response to OPTIONS and will be removed");
                    }

                    regDao.removeRegistration(reg);
                    monitoringService.tell(new UserRegistration(reg.getUserName(), reg.getLocation(), false), self());
                }
            }
        }
    }

    private void patch(final SipURI uri, final String address, final int port) throws UnknownHostException {
        uri.setHost(address);
        uri.setPort(port);
    }

    private boolean permitted(final String authorization, final String method) {
        final Map<String, String> map = toMap(authorization);
        final String user = map.get("username");
        final String algorithm = map.get("algorithm");
        final String realm = map.get("realm");
        final String uri = map.get("uri");
        final String nonce = map.get("nonce");
        final String nc = map.get("nc");
        final String cnonce = map.get("cnonce");
        final String qop = map.get("qop");
        final String response = map.get("response");
        final ClientsDao clients = storage.getClientsDao();
        final Client client = clients.getClient(user);
        if (client != null && Client.ENABLED == client.getStatus()) {
            final String password = client.getPassword();
            final String result = DigestAuthentication.response(algorithm, user, realm, password, nonce, nc, cnonce, method,
                    uri, null, qop);
            return result.equals(response);
        } else {
            return false;
        }
    }

    private void ping(final String to) throws ServletException {
        final SipApplicationSession application = factory.createApplicationSession();
        String toTransport = ((SipURI) factory.createURI(to)).getTransportParam();
        if (toTransport == null) {
            // RESTCOMM-301 NPE in RestComm Ping
            toTransport = "udp";
        }
        if (toTransport.equalsIgnoreCase("ws") || toTransport.equalsIgnoreCase("wss")) {
            return;
        }
        final SipURI outboundInterface = outboundInterface(toTransport);
        StringBuilder buffer = new StringBuilder();
        buffer.append("sip:restcomm").append("@").append(outboundInterface.getHost());
        final String from = buffer.toString();
        final SipServletRequest ping = factory.createRequest(application, "OPTIONS", from, to);
        final SipURI uri = (SipURI) factory.createURI(to);
        ping.pushRoute(uri);
        ping.setRequestURI(uri);
        final SipSession session = ping.getSession();
        session.setHandler("UserAgentManager");
        if(logger.isDebugEnabled()) {
            logger.debug("About to send OPTIONS keepalive to: "+to);
        }
        try {
            ping.send();
        } catch (IOException e) {
            logger.warning("There was an exception trying to ping client: "+to+" , will remove registration. Exception: "+e);
            removeRegistration(ping);
        }
    }

    private void pong(final Object message) {
        final SipServletResponse response = (SipServletResponse) message;
            if (response.getApplicationSession().isValid()) {
                response.getApplicationSession().invalidate();
            }
        final RegistrationsDao registrations = storage.getRegistrationsDao();
        Registration registration = registrations.getRegistration(((SipURI)response.getTo().getURI()).getUser());
        //Registration here shouldn't be null. Update it
        registration = registration.updated();
        registrations.updateRegistration(registration);
    }

    private SipURI outboundInterface(String toTransport) {
        SipURI result = null;
        @SuppressWarnings("unchecked")
        final List<SipURI> uris = (List<SipURI>) servletContext.getAttribute(OUTBOUND_INTERFACES);
        for (final SipURI uri : uris) {
            final String transport = uri.getTransportParam();
            if (toTransport != null && toTransport.equalsIgnoreCase(transport)) {
                result = uri;
            }
        }
        return result;
    }

    private void register(final Object message) throws Exception {
        final SipServletRequest request = (SipServletRequest) message;
        final Address contact = request.getAddressHeader("Contact");
        // Get the expiration time.
        int ttl = contact.getExpires();
        if (ttl == -1) {
            final String expires = request.getHeader("Expires");
            if (expires != null) {
                ttl = parseInt(expires);
            } else {
                ttl = 3600;
            }
        }
        // Make sure registrations don't last more than 1 hour.
        if (ttl > 3600) {
            ttl = 3600;
        }
        // Get the rest of the information needed for a registration record.
        String name = contact.getDisplayName();
        String ua = request.getHeader("User-Agent");
        final SipURI to = (SipURI) request.getTo().getURI();
        final String aor = to.toString();
        final String user = to.getUser();
        final SipURI uri = (SipURI) contact.getURI();
        final String ip = request.getInitialRemoteAddr();
        final int port = request.getInitialRemotePort();
        String transport = (uri.getTransportParam()==null?request.getParameter("transport"):uri.getTransportParam()); //Issue #935, take transport of initial request-uri if contact-uri has no transport parameter
        if (transport == null && !request.getInitialTransport().equalsIgnoreCase("udp")) {
            //Issue1068, if Contact header or RURI doesn't specify transport, check InitialTransport from
            transport = request.getInitialTransport();
        }
        //Issue 306: https://telestax.atlassian.net/browse/RESTCOMM-306
        final String initialIpBeforeLB = request.getHeader("X-Sip-Balancer-InitialRemoteAddr");
        final String initialPortBeforeLB = request.getHeader("X-Sip-Balancer-InitialRemotePort");
        if(initialIpBeforeLB != null && !initialIpBeforeLB.isEmpty() && initialPortBeforeLB != null && !initialPortBeforeLB.isEmpty()) {
            if(logger.isInfoEnabled()) {
                logger.info("Client in front of LB. Patching URI: "+uri.toString()+" with IP: "+initialIpBeforeLB+" and PORT: "+initialPortBeforeLB+" for USER: "+user);
            }
            patch(uri, initialIpBeforeLB, Integer.valueOf(initialPortBeforeLB));
        } else {
            if(logger.isInfoEnabled()) {
                logger.info("Patching URI: " + uri.toString() + " with IP: " + ip + " and PORT: " + port + " for USER: " + user);
            }
            patch(uri, ip, port);
        }

        final StringBuffer buffer = new StringBuffer();
        buffer.append("sip:").append(user).append("@").append(uri.getHost()).append(":").append(uri.getPort());
        // https://bitbucket.org/telestax/telscale-restcomm/issue/142/restcomm-support-for-other-transports-than
        if (transport != null) {
            buffer.append(";transport=").append(transport);
        }
        final String address = buffer.toString();
        // Prepare the response.
        final SipServletResponse response = request.createResponse(SC_OK);
        // Update the data store.
        final Sid sid = Sid.generate(Sid.Type.REGISTRATION);
        final DateTime now = DateTime.now();

        // Issue 87
        // (http://www.google.com/url?q=https://bitbucket.org/telestax/telscale-restcomm/issue/87/verb-and-not-working-for-end-to-end-calls%23comment-5855486&usd=2&usg=ALhdy2_mIt4FU4Yb_EL-s0GZCpBG9BB8eQ)
        // if display name or UA are null, the hasRegistration returns 0 even if there is a registration
        if (name == null)
            name = user;
        if (ua == null)
            ua = "GenericUA";

        boolean webRTC = isWebRTC(transport, ua);

        final Registration registration = new Registration(sid, RestcommConfiguration.getInstance().getMain().getInstanceId(), now, now, aor, name, user, ua, ttl, address, webRTC);
        final RegistrationsDao registrations = storage.getRegistrationsDao();

        if (ttl == 0) {
            // Remove Registration if ttl=0
            registrations.removeRegistration(registration);
            response.setHeader("Expires", "0");
            monitoringService.tell(new UserRegistration(user, address, false), self());
            if(logger.isInfoEnabled()) {
                logger.info("The user agent manager unregistered " + user + " at address "+address);
            }
        } else {
            monitoringService.tell(new UserRegistration(user, address, true), self());
            if (registrations.hasRegistration(registration)) {
                // Update Registration if exists
                registrations.updateRegistration(registration);
                if(logger.isInfoEnabled()) {
                    logger.info("The user agent manager updated " + user + " at address " + address);
                }
            } else {
                // Add registration since it doesn't exists on the DB
                registrations.addRegistration(registration);
                if(logger.isInfoEnabled()) {
                    logger.info("The user agent manager registered " + user + " at address " + address);
                }
            }
            response.setHeader("Contact", contact(uri, ttl));
        }
        // Success
        response.send();
        // Cleanup
        // if(request.getSession().isValid()) {
        // request.getSession().invalidate();
        // }
        try {
            if (request != null) {
                if (request.getApplicationSession() != null) {
                    if (request.getApplicationSession().isValid()) {
                        try {
                            request.getApplicationSession().setInvalidateWhenReady(true);
                        } catch (IllegalStateException exception) {
                            logger.error("Exception while trying to setInvalidateWhenReady(true) for application session, exception: "+exception);
                        }
                    }
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info("After sent response: "+response.toString()+" for Register request, application session is NULL!");
                    }
                }
            } else {
                if (logger.isInfoEnabled()) {
                    logger.info("After sent response: "+response.toString()+" for Register request, request is NULL!");
                }
            }
        } catch (Exception e) {
            logger.error("Exception while trying to setInvalidateWhenReady(true) after sent response to register : "+response.toString()+" exception: "+e);
        }
    }

    /**
     * Checks whether the client is WebRTC or not.
     *
     * <p>
     * A client is considered WebRTC if one of the following statements is true:<br>
     * 1. The chosen transport is WebSockets (transport=ws).<br>
     * 2. The chosen transport is WebSockets Secured (transport=wss).<br>
     * 3. The User-Agent corresponds to one of TeleStax mobile clients.
     * </p>
     *
     * @param transport
     * @param userAgent
     * @return
     */
    private boolean isWebRTC(String transport, String userAgent) {
        return "ws".equalsIgnoreCase(transport) || "wss".equalsIgnoreCase(transport) || userAgent.toLowerCase().contains("restcomm");
    }

    private String contact(final SipURI uri, final int expires) {
        final Address contact = factory.createAddress(uri);
        contact.setExpires(expires);
        return contact.toString();
    }

    private Map<String, String> toMap(final String header) {
        final Map<String, String> map = new HashMap<String, String>();
        final int endOfScheme = header.indexOf(" ");
        map.put("scheme", header.substring(0, endOfScheme).trim());
        final String[] tokens = header.substring(endOfScheme + 1).split(",");
        for (final String token : tokens) {
            final String[] values = token.trim().split("=",2); //Issue #935, split only for first occurrence of "="
            map.put(values[0].toLowerCase(), values[1].replace("\"", ""));
        }
        return map;
    }
}
