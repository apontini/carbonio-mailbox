/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2005, 2006, 2007, 2009, 2010 Zimbra, Inc.
 * 
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.3 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */

package com.zimbra.cs.service.admin;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.zimbra.common.account.Key;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.AdminConstants;
import com.zimbra.common.soap.Element;
import com.zimbra.cs.account.AccountServiceException;
import com.zimbra.cs.account.AttributeClass;
import com.zimbra.cs.account.DistributionList;
import com.zimbra.cs.account.DynamicGroup;
import com.zimbra.cs.account.Group;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.AccessManager.AttrRightChecker;
import com.zimbra.cs.account.accesscontrol.ACLUtil;
import com.zimbra.cs.account.accesscontrol.AdminRight;
import com.zimbra.cs.account.accesscontrol.Right;
import com.zimbra.cs.account.accesscontrol.ZimbraACE;
import com.zimbra.cs.account.accesscontrol.Rights.Admin;
import com.zimbra.cs.account.accesscontrol.Rights.User;
import com.zimbra.soap.ZimbraSoapContext;

public class GetDistributionList extends AdminDocumentHandler {

    /**
     * must be careful and only allow access to domain if domain admin
     */
    public boolean domainAuthSufficient(Map context) {
        return true;
    }

    public Element handle(Element request, Map<String, Object> context) throws ServiceException {
	    
        ZimbraSoapContext zsc = getZimbraSoapContext(context);
        Provisioning prov = Provisioning.getInstance();
	    
        int limit = (int) request.getAttributeLong(AdminConstants.A_LIMIT, 0);
        if (limit < 0) {
        	throw ServiceException.INVALID_REQUEST("limit" + limit + " is negative", null);
        }
        int offset = (int) request.getAttributeLong(AdminConstants.A_OFFSET, 0);
        if (offset < 0) {
        	throw ServiceException.INVALID_REQUEST("offset" + offset + " is negative", null);
        }
        boolean sortAscending = request.getAttributeBool(AdminConstants.A_SORT_ASCENDING, true);
        Set<String> reqAttrs = getReqAttrs(request, AttributeClass.distributionList);
        
        Element d = request.getElement(AdminConstants.E_DL);
        String key = d.getAttribute(AdminConstants.A_BY);
        String value = d.getText();
	    
        Group group = prov.getGroup(Key.DistributionListBy.fromString(key), value);
        
        if (group == null) {
            throw AccountServiceException.NO_SUCH_DISTRIBUTION_LIST(value);
        }
        
        AttrRightChecker arc = null;
        
        if (group.isDynamic()) {
            AdminAccessControl aac = checkDynamicGroupRight(zsc, 
                    (DynamicGroup) group, AdminRight.PR_ALWAYS_ALLOW);
            arc = aac.getAttrRightChecker(group);
        } else {
            AdminAccessControl aac = checkDistributionListRight(zsc, 
                    (DistributionList) group, AdminRight.PR_ALWAYS_ALLOW);
            arc = aac.getAttrRightChecker(group);
        }
        
        Element response = zsc.createElement(AdminConstants.GET_DISTRIBUTION_LIST_RESPONSE);
        Element eDL = encodeDistributionList(response, group, true, reqAttrs, arc);
                
        // return member info only if the authed has right to see zimbraMailForwardingAddress
        boolean allowMembers = true;
        if (group.isDynamic()) {
            allowMembers = arc == null ? true : arc.allowAttr(Provisioning.A_member);
        } else {
            allowMembers = arc == null ? true : arc.allowAttr(Provisioning.A_zimbraMailForwardingAddress);
        }
        
        if (allowMembers) {
            encodeMembers(response, eDL, group, offset, limit, sortAscending);
        }
        
        return response;
    }
    
    private void encodeMembers(Element response, Element dlElement, Group group, 
            int offset, int limit, boolean sortAscending) throws ServiceException {
        String[] members = group.getAllMembers();
        if (offset > 0 && offset >= members.length) {
            throw ServiceException.INVALID_REQUEST("offset " + offset + 
                    " greater than size " + members.length, null);
        }
        int stop = offset + limit;
        if (limit == 0) {
            stop = members.length;
        }
        if (stop > members.length) {
            stop = members.length;
        }
        
        if (sortAscending) {
            Arrays.sort(members);
        } else {
            Arrays.sort(members, Collections.reverseOrder());
        }
        for (int i = offset; i < stop; i++) {
            dlElement.addElement(AdminConstants.E_DLM).setText(members[i]);
        }
        
        response.addAttribute(AdminConstants.A_MORE, stop < members.length);
        response.addAttribute(AdminConstants.A_TOTAL, members.length);
    }

    public static Element encodeDistributionList(Element e, Group group) 
    throws ServiceException {
        return encodeDistributionList(e, group, true, null, null);
    }
    
    public static Element encodeDistributionList(Element e, Group group, 
            boolean hideMembers, Set<String> reqAttrs, AttrRightChecker attrRightChecker) 
    throws ServiceException {
        Element eDL = e.addElement(AdminConstants.E_DL);
        eDL.addAttribute(AdminConstants.A_NAME, group.getUnicodeName());
        eDL.addAttribute(AdminConstants.A_ID,group.getId());
        eDL.addAttribute(AdminConstants.A_DYNAMIC, group.isDynamic());
        
        Set<String> hideAttrs = null;
        if (hideMembers) {
            hideAttrs = new HashSet<String>();
            if (group.isDynamic()) {
                hideAttrs.add(Provisioning.A_member);
            } else {
                hideAttrs.add(Provisioning.A_zimbraMailForwardingAddress);
            }
        }
        
        encodeOwners(eDL, group);
        
        ToXML.encodeAttrs(eDL, group.getUnicodeAttrs(), 
                AdminConstants.A_N, reqAttrs, hideAttrs, attrRightChecker);
        
        return eDL;
    }
    
    private static Element encodeOwners(Element eDL, Group group) throws ServiceException {
        Element eOwners = null;
        
        List<ZimbraACE> acl = ACLUtil.getAllACEs(group);
        if (acl != null) {
            for (ZimbraACE ace : acl) {
                Right right = ace.getRight();
                if (User.R_ownDistList == right) {
                    if (eOwners == null) {
                        eOwners = eDL.addElement(AdminConstants.E_DL_OWNERS);
                    }
                    
                    // encode the owner
                    Element eOwner = eOwners.addElement(AdminConstants.E_DL_OWNER);
                    
                    eOwner.addAttribute(AdminConstants.A_TYPE, ace.getGranteeType().getCode());
                    eOwner.addAttribute(AdminConstants.A_ID, ace.getGrantee());
                    eOwner.addAttribute(AdminConstants.A_NAME, ace.getGranteeDisplayName());
                }
            }
        }
        
        return eOwners;
    }

    @Override
    public void docRights(List<AdminRight> relatedRights, List<String> notes) {
        relatedRights.add(Admin.R_getDistributionList);
        relatedRights.add(Admin.R_getGroup);
        notes.add(String.format(AdminRightCheckPoint.Notes.GET_ENTRY, Admin.R_getDistributionList.getName()));
        notes.add(String.format(AdminRightCheckPoint.Notes.GET_ENTRY, Admin.R_getGroup.getName()));
    }
}
