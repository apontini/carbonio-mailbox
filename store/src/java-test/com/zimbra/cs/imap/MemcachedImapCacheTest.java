// SPDX-FileCopyrightText: 2022 Synacor, Inc.
// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: GPL-2.0-only

package com.zimbra.cs.imap;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.ObjectOutputStream;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.MethodRule;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import com.zimbra.common.util.memcached.ZimbraMemcachedClient;
import com.zimbra.cs.mailbox.MailboxTestUtil;
import com.zimbra.cs.memcached.MemcachedConnector;
import com.zimbra.cs.util.ZTestWatchman;

/**
 * @author zimbra
 *
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({ImapFolder.class, MemcachedImapCache.class, MemcachedConnector.class, ZimbraMemcachedClient.class})
public class MemcachedImapCacheTest {

    @Rule public TestName testName = new TestName();
    @Rule public MethodRule watchman = new ZTestWatchman();
    /**
     * @throws java.lang.Exception
     */
    @Before
    public void setUp() throws Exception {
        MailboxTestUtil.initProvisioning("store/");
    }

    /**
     * @throws java.lang.Exception
     */
    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testInvalidObject() {
        try {

            PowerMockito.mockStatic(MemcachedConnector.class);
            ZimbraMemcachedClient  memcachedClient = new MockZimbraMemcachedClient();
            PowerMockito.when(MemcachedConnector.getClient()).thenReturn(memcachedClient);
            ImapFolder folder = PowerMockito.mock(ImapFolder.class);
            MemcachedImapCache imapCache = new MemcachedImapCache();
            imapCache.put("trash", folder);
            ImapFolder folderDeserz =  imapCache.get("trash");
            assertNull(folderDeserz);
        } catch (Exception e) {
            fail("Exception should not be thrown");
        }
    }

    public class MockZimbraMemcachedClient extends ZimbraMemcachedClient {

        @Override
        public Object get(String key) {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            try {
                if (key.equals("zmImap:trash")) {
                    ObjectOutputStream oout = new ObjectOutputStream(bout);
                    oout.writeObject(new Hacker("hacked"));
                    oout.close();
                }
            } catch (Exception e) {
                return bout.toByteArray();
            }
           return bout.toByteArray();
        }

        @Override
        public boolean put(String key, Object value, boolean waitForAck) {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            try (ObjectOutputStream oout = new ObjectOutputStream(bout)) {
                oout.writeObject(new Hacker("hacked"));
            } catch (Exception e) {
                return false;
            }
            return true;
        }
    }
}
