/**
 * ==========================================================================================
 * =                   JAHIA'S DUAL LICENSING - IMPORTANT INFORMATION                       =
 * ==========================================================================================
 *
 *                                 http://www.jahia.com
 *
 *     Copyright (C) 2002-2017 Jahia Solutions Group SA. All rights reserved.
 *
 *     THIS FILE IS AVAILABLE UNDER TWO DIFFERENT LICENSES:
 *     1/GPL OR 2/JSEL
 *
 *     1/ GPL
 *     ==================================================================================
 *
 *     IF YOU DECIDE TO CHOOSE THE GPL LICENSE, YOU MUST COMPLY WITH THE FOLLOWING TERMS:
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 *
 *     2/ JSEL - Commercial and Supported Versions of the program
 *     ===================================================================================
 *
 *     IF YOU DECIDE TO CHOOSE THE JSEL LICENSE, YOU MUST COMPLY WITH THE FOLLOWING TERMS:
 *
 *     Alternatively, commercial and supported versions of the program - also known as
 *     Enterprise Distributions - must be used in accordance with the terms and conditions
 *     contained in a separate written agreement between you and Jahia Solutions Group SA.
 *
 *     If you are unsure which license is appropriate for your use,
 *     please contact the sales department at sales@jahia.com.
 */
package org.jahia.modules.newsletter.action;

import org.apache.commons.lang.StringUtils;
import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.bin.Jahia;
import org.jahia.bin.Render;
import org.jahia.data.templates.JahiaTemplatesPackage;
import org.jahia.modules.newsletter.service.SubscriptionService;
import org.jahia.registries.ServicesRegistry;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRUserNode;
import org.jahia.services.mail.MailService;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.jcr.RepositoryException;
import javax.script.ScriptException;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;

/**
 * An action for subscribing a user to the target node.
 * 
 * @author Sergiy Shyrkov
 */
public class UnsubscribeAction extends Action {

	private static final Logger logger = LoggerFactory.getLogger(UnsubscribeAction.class);

	private boolean forceConfirmationForRegisteredUsers;

    private String mailConfirmationTemplate = null;

    @Autowired
    private MailService mailService;
    @Autowired
	private SubscriptionService subscriptionService;
    @Autowired
    private transient JahiaUserManagerService userManagerService;

    public static String generateUnsubscribeLink(JCRNodeWrapper newsletterNode, String confirmationKey,
	        HttpServletRequest req) throws RepositoryException {
		return req.getScheme() + "://" + req.getServerName() + ":" + req.getServerPort()
		        + Jahia.getContextPath() + Render.getRenderServletPath() + "/live/"
		        + newsletterNode.getLanguage() + newsletterNode.getPath()
		        + ".confirm.do?key=" + confirmationKey + "&exec=rem";
	}
	
	/**
	* Check if templatSet has mail template
	*/
    private String getTemplateName(String template, JCRNodeWrapper node,  final Locale locale, String defaultTemplate){
    	String templateToReturn = defaultTemplate;

    	try {
	    	//try if it is multilingual
	        String suffix = StringUtils.substringAfterLast(template, ".");
	    	String languageMailConfTemplate = template.substring(0, template.length() - (suffix.length()+1)) + "_" + locale.toString() + "." + suffix;
	    	String templatePackageName = node.getResolveSite().getTemplatePackageName();
	    	JahiaTemplatesPackage templatePackage = ServicesRegistry.getInstance().getJahiaTemplateManagerService().getTemplatePackage(templatePackageName);
	    	org.springframework.core.io.Resource templateRealPath = templatePackage.getResource(languageMailConfTemplate);
	    	if(templateRealPath == null) {
	          templateRealPath = templatePackage.getResource(template);
	    	}
	    	if (templateRealPath!=null){
	    		templateToReturn = templatePackageName;
	    	}
    	} catch (Exception ue){
    		logger.error("Error resolving template for site");
    	}

    	return templateToReturn;
    }

    public ActionResult doExecute(final HttpServletRequest req, final RenderContext renderContext,
                                  final Resource resource, JCRSessionWrapper session, final Map<String, List<String>> parameters, URLResolver urlResolver)
            throws Exception {

        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, "live", new JCRCallback<ActionResult>() {
            public ActionResult doInJCR(JCRSessionWrapper session) throws RepositoryException {
                try {
                    String email = getParameter(parameters, "email");
                    final JCRNodeWrapper node = resource.getNode();
                    session.checkout(node);
                    if (email != null) {
                        // consider as non-registered user
                        JCRNodeWrapper subscription = subscriptionService.getSubscription(node, email, session);

                        if (subscription == null) {
                            return new ActionResult(SC_OK, null, new JSONObject("{\"status\":\"invalid-user\"}"));
                        } else if (sendConfirmationMail(session, email, node, subscription, resource, req)) {
                            return new ActionResult(SC_OK, null, new JSONObject("{\"status\":\"mail-sent\"}"));
                        }

                        subscriptionService.cancel(subscription.getIdentifier(), session);
                    } else {
                        JahiaUser user = renderContext.getUser();
                        JCRUserNode userNode = userManagerService.lookupUserByPath(user.getLocalPath());
                        if (JahiaUserManagerService.isGuest(user) || userNode == null) {
                            // anonymous users are not allowed (and no email was provided)
                            return new ActionResult(SC_OK, null, new JSONObject("{\"status\":\"invalid-user\"}"));
                        }

                        JCRNodeWrapper subscription = subscriptionService.getSubscription(node, user.getUserKey(), session);

                        if (subscription == null) {
                            return new ActionResult(SC_OK, null, new JSONObject("{\"status\":\"invalid-user\"}"));
                        } else if (forceConfirmationForRegisteredUsers && sendConfirmationMail(session, userNode.getPropertyAsString("j:email"), node, subscription, resource, req)) {
                        	return new ActionResult(SC_OK, null, new JSONObject("{\"status\":\"mail-sent\"}"));
                        }

                        subscriptionService.cancel(subscription.getIdentifier(), session);
                    }
                    return new ActionResult(SC_OK, null, new JSONObject("{\"status\":\"ok\"}"));
                } catch (JSONException e) {
                    logger.error("Error",e);
                    return new ActionResult(SC_INTERNAL_SERVER_ERROR, null, null);
                }
            }
        });

    }

    private boolean sendConfirmationMail(JCRSessionWrapper session, String email, JCRNodeWrapper node, JCRNodeWrapper subscription,
                                         Resource resource, HttpServletRequest req) throws RepositoryException, JSONException {
        if (mailConfirmationTemplate != null) {
            session.checkout(subscription);
            String confirmationKey = subscriptionService.generateConfirmationKey(subscription);
            subscription.setProperty(SubscriptionService.J_CONFIRMATION_KEY, confirmationKey);
            session.save();

            Map<String, Object> bindings = new HashMap<String, Object>();
            bindings.put("newsletter", node);
            bindings.put("confirmationlink", generateUnsubscribeLink(node, confirmationKey, req));
            try {
            	String modulePackageNameToUse = getTemplateName(mailConfirmationTemplate,node, resource.getLocale(),"Jahia Newsletter");
				String mailSender = mailService.defaultSender();

		        try {
		            JCRSiteNode siteNode = node.getResolveSite();

		            if (siteNode.isNodeType("jmix:newsletterSender")) {
		                String newMailSender = siteNode.getPropertyAsString(
		                        "newsletterMailSender");

		                if ((newMailSender != null) &&
		                        !"".equals(newMailSender.trim())) {
		                    mailSender = newMailSender;
		                }
		            }
		        } catch (Exception ue) {
		            logger.debug(ue.getMessage(), ue);
		        }
                mailService.sendMessageWithTemplate(mailConfirmationTemplate, bindings, email, mailSender, null, null, resource.getLocale(), modulePackageNameToUse);
            } catch (ScriptException e) {
                logger.error("Cannot generate confirmation mail",e);
            }

            return true;
        }
        return false;
    }

    public void setMailConfirmationTemplate(String mailConfirmationTemplate) {
        this.mailConfirmationTemplate = mailConfirmationTemplate;
    }

    public void setMailService(MailService mailService) {
        this.mailService = mailService;
    }

	public void setSubscriptionService(SubscriptionService subscriptionService) {
		this.subscriptionService = subscriptionService;
	}

    public void setUserManagerService(JahiaUserManagerService userManagerService) {
        this.userManagerService = userManagerService;
    }

    public void setForceConfirmationForRegisteredUsers(boolean forceConfirmationForRegisteredUsers) {
    	this.forceConfirmationForRegisteredUsers = forceConfirmationForRegisteredUsers;
    }

}