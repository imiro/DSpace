/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.authenticate;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.log4j.Logger;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.core.Context;
import org.dspace.core.LogManager;
import org.dspace.authenticate.factory.AuthenticateServiceFactory;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;

// we use the Java CAS client
import edu.yale.its.tp.cas.client.*;
import java.util.ArrayList;
import org.jasig.cas.client.validation.Assertion;
import org.jasig.cas.client.validation.Cas20ServiceTicketValidator;

/**
 * Authenticator for Central Authentication Service (CAS).
 *
 * @author Naveed Hashmi, University of Bristol
 * based on code developed by Nordija A/S (www.nordija.com) for Center of Knowledge Technology (www.cvt.dk)
 * @version $Revision: 1.0 $
 * @author Nicolás Kovac Neumann, Universidad de La Laguna
 * CAS authentication has been adapted to DSpace 3.1 (latest stable) and proved functionality with CAS 3.5.0
 * @version $Revision: 1.1 $
 * @author Tomasz Baria Boiński, Gdańsk University of Technology
 * CAS authentication has been adapted to DSpace 4.2 and integrated with SAML user query
 * @version $Revision 1.2 $
 */

public class CASAuthentication
    implements AuthenticationMethod {

    /** log4j category */
    private static Logger log = Logger.getLogger(CASAuthentication.class);

    private static String casProxyvalidate;   //URL to validate PT tickets

    // (optional) store user's details for self registration, can get this info from LDAP, RDBMS etc
    private String firstName = "University";
    private String lastName = "User";
    private String email = null;

    private Map attributes = null;

    protected ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
    /**
     * Predicate, can new user automatically create EPerson.
     * Checks configuration value.  You'll probably want this to
     * be true to take advantage of a Web certificate infrastructure
     * with many more users than are already known by DSpace.
     */
    public boolean canSelfRegister(Context context,
                                   HttpServletRequest request,
                                   String username)
        throws SQLException
    {
        return configurationService.getBooleanProperty("authentication-cas.webui.cas.autoregister");
    }

    /**
     *  Nothing extra to initialize.
     */
    public void initEPerson(Context context, HttpServletRequest request,
            EPerson eperson)
        throws SQLException
    {
    }

    /**
     * We don't use EPerson password so there is no reason to change it.
     */
    public boolean allowSetPassword(Context context,
                                    HttpServletRequest request,
                                    String username)
        throws SQLException
    {
        return false;
    }

    /**
     * 
     * Predicate, is this an implicit authentication method.
     * An implicit method gets credentials from the environment (such as
     * an HTTP request or even Java system properties) rather than the
     * explicit username and password.  For example, a method that reads
     * the X.509 certificates in an HTTPS request is implicit.
     * @return true if this method uses implicit authentication.
     * 
     * Returns true, CAS is an implicit method
     */
    public boolean isImplicit()
    {
        return true;
    }

    /**
     * No special groups.
     */
    public List<Group> getSpecialGroups(Context context, HttpServletRequest request)
    {
	    return new ArrayList();
    }


    /**
     * CAS authentication.
     *
     * @return One of: SUCCESS, BAD_CREDENTIALS, NO_SUCH_USER, BAD_ARGS
     */
    public int authenticate(Context context,
                            String netid,
                            String password,
                            String realm,
                            HttpServletRequest request)
        throws SQLException
    {
        final String ticket = request.getParameter("ticket");
        final String service = request.getRequestURL().toString();
        log.info(LogManager.getHeader(context, "login", " ticket= " + ticket));
        log.info(LogManager.getHeader(context, "login", "service= " + service));

        if (ticket != null)
        {
            try
            {
                // Determine CAS validation URL
                String validate = configurationService.getProperty("authentication-cas.cas.validate.url", null);
                log.info(LogManager.getHeader(context, "login", "CAS ticket: " + ticket));
                log.info(LogManager.getHeader(context, "login", "CAS service: " + service));
                if (validate == null)
                {
                    throw new ServletException("No CAS validation URL specified. You need to set property 'cas.validate.url'");
                }

                // Validate ticket (it is assumed that CAS validator returns the user network ID)
                netid = validate(service, ticket, validate);
                if (netid == null)
                {
                    throw new ServletException("Ticket '" + ticket + "' is not valid");
                }

                // Locate the eperson in DSpace
                EPerson eperson = null;
                try
                {
                    eperson = EPersonServiceFactory.getInstance().getEPersonService().findByNetid(context, netid.toLowerCase());
                }
                catch (SQLException e)
                {
                  log.error("cas findbynetid failed");
                  log.error(e.getStackTrace());
                }

                // if they entered a netd that matches an eperson and they are allowed to login
                if (eperson != null)
                {
                  // e-mail address corresponds to active account
                    if (eperson.getRequireCertificate())
                    {
                        // they must use a certificate
                        return CERT_REQUIRED;
                    }
                    else if (!eperson.canLogIn()) {
                        return BAD_ARGS;
                    }

                    // Logged in OK.
                    HttpSession session = request.getSession(false);
                    if (session!=null) {
                      session.setAttribute("loginType", "CAS");
                    }

                    context.setCurrentUser(eperson);
                    log.info(LogManager.getHeader(context, "authenticate", "type=CAS"));

                    return SUCCESS;
                }

                // the user does not exist in DSpace so create an eperson
                else
                {
                  if (canSelfRegister(context, request, netid) )
                    {
                        //org.jasig.cas.client.validation.Saml11TicketValidationFilter filter;
                        // TEMPORARILY turn off authorisation
                        // Register the new user automatically
                        //context.setIgnoreAuthorization(true);
                        context.turnOffAuthorisationSystem();
                        
                        eperson = EPersonServiceFactory.getInstance().getEPersonService().create(context);
                        // use netid only but this implies that user has to manually update their profile
                        eperson.setNetid(netid);

                        // if you wish to automatically extract further user details: email, first_name and last_name
                        //  enter your method here: e.g. query LDAP or RDBMS etc.
                        /* e.g.
                        registerUser(netid);
                        eperson.setEmail(email);*/

                        eperson.setFirstName(context, firstName);
                        eperson.setLastName(context, lastName);
                        
                        if (email == null) {
                            email = netid;
                        }
                        
                        String lang = configurationService.getProperty("default.locale");
                        eperson.setLanguage(context, lang);
                        eperson.setEmail(email);
                        eperson.setRequireCertificate(false);
                        eperson.setSelfRegistered(false);

                        eperson.setCanLogIn(true);
                        AuthenticateServiceFactory.getInstance().getAuthenticationService().initEPerson(context, request, eperson);
                        // eperson.update();
                        context.commit();
                        //context.setIgnoreAuthorization(false);
                        context.restoreAuthSystemState();
                        context.setCurrentUser(eperson);
                        log.warn(LogManager.getHeader(context, "authenticate",
                            netid + "  type=CAS auto-register"));
                        return SUCCESS;
                    }
                    else
                    {
                        // No auto-registration for valid netid
                        log.warn(LogManager.getHeader(context, "authenticate",
                            netid + "  type=netid_but_no_record, cannot auto-register"));
                        return NO_SUCH_USER;
                    }
                }

            } catch (Exception e)
            {
                log.error(e.getStackTrace()[0]);
                //throw new ServletException(e);
            }
        }
        return BAD_ARGS;
    }


  /**
   * Returns the NetID of the owner of the given ticket, or null if the
   * ticket isn't valid.
   *
   * @param service the service ID for the application validating the
   *                ticket
   * @param ticket  the opaque service ticket (ST) to validate
   * 
   * @param validateURL the validation service URL
   * 
   * @return netid of eperson
   * 
   * @throws java.io.IOException
   * 
   * @throws javax.servlet.ServletException
   */
  public String validate(String service, String ticket, String validateURL)
      throws IOException, ServletException
  {
	String netid = null;
	firstName = "University";
	lastName = "User";
	email = null;

	ServiceTicketValidator stv = null;
	String validateUrl = null;

	if (ticket.startsWith("ST")) {
	stv = new ServiceTicketValidator();
	} else {
	// uPortal uses this
	stv = new ProxyTicketValidator();
	validateUrl = casProxyvalidate;
	}

	stv.setCasValidateUrl(validateURL);
	stv.setService(java.net.URLEncoder.encode(service));
	stv.setServiceTicket(ticket);

	try {
		stv.validate();
	} catch (Exception e) {
		log.error("Unexpected exception caught", e);
		throw new ServletException(e);
	}

	if (!stv.isAuthenticationSuccesful()) {
		return null;
	}
	netid = stv.getUser();
//	attributes = stv.getAttributes();
	log.info("Authenticated user via CAS: " + netid);
	return netid;
  }


    /**
    * Add code here to extract user details
    * email, firstname, lastname
    * from LDAP or database etc
    */

   public void registerUser(String netid)
                          throws ClassNotFoundException, SQLException
    {
                // add your code here
    }


    /*
     * Returns URL to which to redirect to obtain credentials (either password
     * prompt or e.g. HTTPS port for client cert.); null means no redirect.
     *
     * @param context
     *  DSpace context, will be modified (ePerson set) upon success.
     *
     * @param request
     *  The HTTP request that started this operation, or null if not applicable.
     *
     * @param response
     *  The HTTP response from the servlet method.
     *
     * @return fully-qualified URL
     */
    public String loginPageURL(Context context,
                            HttpServletRequest request,
                            HttpServletResponse response)
    {
       // Determine CAS server URL
       final String authServer = configurationService.getProperty("authentication-cas.cas.server.url");
       StringBuffer url=new StringBuffer(authServer);
       url.append("?service=").append(request.getScheme()).
       append("://").append(request.getServerName());
       //Add the URL callback
       if(request.getServerPort()!=80)
         url.append(":").append(request.getServerPort());
       url.append(request.getContextPath()).append("/cas-login");
       log.info("CAS server and service:  " + authServer);
       //System.out.println(url);
       
       // Redirect to CAS server
       return response.encodeRedirectURL(url.toString());
    }

    /*
     * Returns message key for title of the "login" page, to use
     * in a menu showing the choice of multiple login methods.
     *
     * @param context
     *  DSpace context, will be modified (ePerson set) upon success.
     *
     * @return Message key to look up in i18n message catalog.
     */
    public String loginPageTitle(Context context)
    {
        //return null;
        return "org.dspace.eperson.CASAuthentication.title";
    }

}

