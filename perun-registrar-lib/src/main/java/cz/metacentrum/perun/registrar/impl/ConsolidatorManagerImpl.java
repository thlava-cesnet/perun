package cz.metacentrum.perun.registrar.impl;

import cz.metacentrum.perun.core.api.*;
import cz.metacentrum.perun.core.api.exceptions.PerunException;
import cz.metacentrum.perun.core.api.exceptions.PrivilegeException;
import cz.metacentrum.perun.core.bl.PerunBl;
import cz.metacentrum.perun.core.impl.Utils;
import cz.metacentrum.perun.registrar.ConsolidatorManager;
import cz.metacentrum.perun.registrar.RegistrarManager;
import cz.metacentrum.perun.registrar.model.Application;
import cz.metacentrum.perun.registrar.model.ApplicationFormItemData;
import cz.metacentrum.perun.registrar.model.Identity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.*;

/**
 *
 *
 * @author Pavel Zlámal <zlamal@cesnet.cz>
 */
public class ConsolidatorManagerImpl implements ConsolidatorManager {

	final static Logger log = LoggerFactory.getLogger(ConsolidatorManagerImpl.class);

	@Autowired RegistrarManager registrarManager;
	@Autowired PerunBl perun;
	private JdbcTemplate jdbc;
	private PerunSession registrarSession;

	public void setRegistrarManager(RegistrarManager registrarManager) {
		this.registrarManager = registrarManager;
	}

	public void setDataSource(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	protected void initialize() throws PerunException {

		// gets session for a system principal "perunRegistrar"
		final PerunPrincipal pp = new PerunPrincipal("perunRegistrar",
				ExtSourcesManager.EXTSOURCE_NAME_INTERNAL,
				ExtSourcesManager.EXTSOURCE_INTERNAL);
		registrarSession = perun.getPerunSession(pp);

	}

	@Override
	public List<Identity> checkForSimilarUsers(PerunSession sess) throws PerunException {

		// if user known, doesn't actually search and offer joining.
		if (sess.getPerunPrincipal().getUser() != null) {
			return new ArrayList<Identity>();
		}

		// if user known, doesn't actually search and offer joining.
		try {
			perun.getUsersManager().getUserByExtSourceNameAndExtLogin(registrarSession, sess.getPerunPrincipal().getExtSourceName(), sess.getPerunPrincipal().getActor());
			return new ArrayList<Identity>();
		} catch (Exception ex) {
			// we don't care, that search failed. That is actually OK case.
		}

		String name = "";
		String mail = "";

		Set<RichUser> res = new HashSet<RichUser>();

		List<String> attrNames = new ArrayList<String>();
		attrNames.add("urn:perun:user:attribute-def:def:preferredMail");
		attrNames.add("urn:perun:user:attribute-def:def:organization");

		mail = sess.getPerunPrincipal().getAdditionalInformations().get("mail");

		if (mail != null && !mail.isEmpty()) res.addAll(perun.getUsersManager().findRichUsersWithAttributes(registrarSession, mail, attrNames));

		// check by mail is more precise, so check by name only if nothing is found.
		if (res == null || res.isEmpty()) {

			name = sess.getPerunPrincipal().getAdditionalInformations().get("cn");

			if (name != null && !name.isEmpty()) res.addAll(perun.getUsersManager().findRichUsersWithAttributes(registrarSession, name, attrNames));

			name = sess.getPerunPrincipal().getAdditionalInformations().get("displayName");

			if (name != null && !name.isEmpty()) res.addAll(perun.getUsersManager().findRichUsersWithAttributes(registrarSession, name, attrNames));

		}

		return convertToIdentities(new ArrayList<RichUser>(res));

	}

	@Override
	public List<Identity> checkForSimilarUsers(PerunSession sess, Vo vo, Group group, Application.AppType type) throws PerunException {

		Application app = getLatestApplication(sess, vo, group, type);
		if (app != null) {
			return checkForSimilarUsers(sess, app.getId());
		} else {
			return new ArrayList<Identity>();
		}
	}

	@Override
	public List<Identity> checkForSimilarUsers(PerunSession sess, int appId) throws PerunException {

		String email = "";
		String name = "";
		List<RichUser> result = new ArrayList<RichUser>();

		List<String> attrNames = new ArrayList<String>();
		attrNames.add("urn:perun:user:attribute-def:def:preferredMail");
		attrNames.add("urn:perun:user:attribute-def:def:organization");

		Application app = registrarManager.getApplicationById(registrarSession, appId);

		if (app.getGroup() == null) {
			if (!AuthzResolver.isAuthorized(sess, Role.VOADMIN, app.getVo())) {
				if (sess.getPerunPrincipal().getUser() != null) {
					// check if application to find similar users by belongs to user
					if (!sess.getPerunPrincipal().getUser().equals(app.getUser())) throw new PrivilegeException("checkForSimilarUsers");
				} else {
					if (!sess.getPerunPrincipal().getExtSourceName().equals(app.getExtSourceName()) &&
							!sess.getPerunPrincipal().getActor().equals(app.getCreatedBy())) throw new PrivilegeException("checkForSimilarUsers");
				}
			}
		} else {
			if (!AuthzResolver.isAuthorized(sess, Role.VOADMIN, app.getVo()) &&
					!AuthzResolver.isAuthorized(sess, Role.GROUPADMIN, app.getGroup())) {
				if (sess.getPerunPrincipal().getUser() != null) {
					// check if application to find similar users by belongs to user
					if (!sess.getPerunPrincipal().getUser().equals(app.getUser())) throw new PrivilegeException("checkForSimilarUsers");
				} else {
					if (!sess.getPerunPrincipal().getExtSourceName().equals(app.getExtSourceName()) &&
							!sess.getPerunPrincipal().getActor().equals(app.getCreatedBy())) throw new PrivilegeException("checkForSimilarUsers");
				}
			}
		}

		// only for initial VO applications if user==null
		if (app.getType().equals(Application.AppType.INITIAL) && app.getGroup() == null && app.getUser() == null) {

			try {
				User u = perun.getUsersManager().getUserByExtSourceNameAndExtLogin(registrarSession, app.getExtSourceName(), app.getCreatedBy());
				if (u != null) {
					// user connected his identity after app creation and before it's approval.
					// do not show error message in GUI by returning an empty array.
					return convertToIdentities(result);
				}
			} catch (Exception ex){
				// we don't care, let's try to search by name
			}

			List<ApplicationFormItemData> data = registrarManager.getApplicationDataById(sess, appId);

			// search by email, which should be unique (check is more precise)
			for (ApplicationFormItemData item : data) {
				if ("urn:perun:user:attribute-def:def:preferredMail".equals(item.getFormItem().getPerunDestinationAttribute())) {
					email = item.getValue();
				}
				if (email != null && !email.isEmpty()) break;
			}

			List<RichUser> users = (email != null && !email.isEmpty()) ? perun.getUsersManager().findRichUsersWithAttributes(registrarSession, email, attrNames) : new ArrayList<RichUser>();

			if (users != null && !users.isEmpty()) {
				// found by preferredMail
				return convertToIdentities(users);
			}

			// search by different mail

			email = ""; // clear previous value
			for (ApplicationFormItemData item : data) {
				if ("urn:perun:member:attribute-def:def:mail".equals(item.getFormItem().getPerunDestinationAttribute())) {
					email = item.getValue();
				}
				if (email != null && !email.isEmpty()) break;
			}

			users = (email != null && !email.isEmpty()) ? perun.getUsersManager().findRichUsersWithAttributes(registrarSession, email, attrNames) : new ArrayList<RichUser>();
			if (users != null && !users.isEmpty()) {
				// found by member mail
				return convertToIdentities(users);
			}

			// continue to search by display name

			for (ApplicationFormItemData item : data) {
				if (RegistrarManagerImpl.URN_USER_DISPLAY_NAME.equals(item.getFormItem().getPerunDestinationAttribute())) {
					name = item.getValue();
					// use parsed name to drop mistakes on IDP side
					try {
						Map<String, String> nameMap = Utils.parseCommonName(name);
						// drop name titles to spread search
						String newName = "";
						if (nameMap.get("firstName") != null
								&& !nameMap.get("firstName").isEmpty()) {
							newName += nameMap.get("firstName") + " ";
						}
						if (nameMap.get("lastName") != null
								&& !nameMap.get("lastName").isEmpty()) {
							newName += nameMap.get("lastName");
						}
						// fill parsed name instead of input
						if (newName != null && !newName.isEmpty()) {
							name = newName;
						}
					} catch (Exception ex) {
						log.error("[REGISTRAR] Unable to parse new user's display/common name when searching for similar users. Exception: {}", ex);
					}
					if (name != null && !name.isEmpty()) break;
				}
			}

			users = (name != null && !name.isEmpty()) ? perun.getUsersManager().findRichUsersWithAttributes(registrarSession, name, attrNames) : new ArrayList<RichUser>();
			if (users != null && !users.isEmpty()) {
				// found by member display name
				return convertToIdentities(users);
			}

			// continue to search by last name

			name = ""; // clear previous value
			for (ApplicationFormItemData item : data) {
				if (RegistrarManagerImpl.URN_USER_LAST_NAME.equals(item.getFormItem().getPerunDestinationAttribute())) {
					name = item.getValue();
					if (name != null && !name.isEmpty()) break;
				}
			}

			if (name != null && !name.isEmpty()) {
				// what was found by name
				return convertToIdentities(perun.getUsersManager().findRichUsersWithAttributes(registrarSession, name, attrNames));
			} else {
				// not found by name
				return convertToIdentities(result);
			}

		} else {
			// not found, since not proper type of application to check users for
			return convertToIdentities(result);
		}

	}

	/**
	 * Retrieves whole application object from DB
	 * (authz in parent methods)
	 *
	 * @param sess PerunSession for Authz and to resolve User
	 * @param vo VO to get application for
	 * @param group Group
	 *
	 * @return application object / null if not exists
	 */
	private Application getLatestApplication(PerunSession sess, Vo vo, Group group, Application.AppType type) {
		try {

			if (sess.getPerunPrincipal().getUser() != null) {

				if (group != null) {

					return jdbc.queryForObject(RegistrarManagerImpl.APP_SELECT + " where a.id=(select max(id) from application where vo_id=? and group_id=? and apptype=? and user_id=? )", RegistrarManagerImpl.APP_MAPPER, vo.getId(), group.getId(), String.valueOf(type), sess.getPerunPrincipal().getUserId());

				} else {

					return jdbc.queryForObject(RegistrarManagerImpl.APP_SELECT + " where a.id=(select max(id) from application where vo_id=? and apptype=? and user_id=? )", RegistrarManagerImpl.APP_MAPPER, vo.getId(), String.valueOf(type), sess.getPerunPrincipal().getUserId());

				}

			} else {

				if (group != null) {

					return jdbc.queryForObject(RegistrarManagerImpl.APP_SELECT + " where a.id=(select max(id) from application where vo_id=? and group_id=? and apptype=? and created_by=? and extsourcename=? )", RegistrarManagerImpl.APP_MAPPER, vo.getId(), group.getId(), String.valueOf(type), sess.getPerunPrincipal().getActor(), sess.getPerunPrincipal().getExtSourceName());

				} else {

					return jdbc.queryForObject(RegistrarManagerImpl.APP_SELECT + " where a.id=(select max(id) from application where vo_id=? and apptype=? and created_by=? and extsourcename=? )", RegistrarManagerImpl.APP_MAPPER, vo.getId(), String.valueOf(type), sess.getPerunPrincipal().getActor(), sess.getPerunPrincipal().getExtSourceName());

				}

			}

		} catch (EmptyResultDataAccessException ex) {
			return null;
		}
	}

	private List<Identity> convertToIdentities(List<RichUser> list) throws PerunException {

		List<Identity> result = new ArrayList<Identity>();

		if (list != null && !list.isEmpty()) {

			for (RichUser u : list) {

				Identity identity = new Identity();
				identity.setName(u.getDisplayName());

				for (Attribute a : u.getUserAttributes()) {

					if (MailManagerImpl.URN_USER_PREFERRED_MAIL.equals(a.getName())) {
						if (a.getValue() != null && !((String)a.getValue()).isEmpty()) {

							String safeMail = ((String) a.getValue()).split("@")[0];

							if (safeMail.length() > 2) {
								safeMail = safeMail.substring(0, 1) + "***" + safeMail.substring(safeMail.length()-2, safeMail.length()-1);
							}

							safeMail += "@"+((String) a.getValue()).split("@")[1];

							identity.setEmail(safeMail);

						}
					} else if ("urn:perun:user:attribute-def:def:organization".equals(a.getName())) {
						if (a.getValue() != null) {
							identity.setOrganization((String)a.getValue());
						}
					}

				}

				List<ExtSource> es = new ArrayList<ExtSource>();
				for (UserExtSource ues : u.getUserExtSources()) {
					// TODO config offered
					es.add(ues.getExtSource());
				}
				identity.setIdentities(es);

				result.add(identity);

			}

		}

		return result;

	}

}