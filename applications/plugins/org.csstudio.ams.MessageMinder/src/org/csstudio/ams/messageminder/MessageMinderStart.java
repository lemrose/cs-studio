
/*
 * Copyright (c) 2007 Stiftung Deutsches Elektronen-Synchrotron,
 * Member of the Helmholtz Association, (DESY), HAMBURG, GERMANY.
 *
 * THIS SOFTWARE IS PROVIDED UNDER THIS LICENSE ON AN "../AS IS" BASIS.
 * WITHOUT WARRANTY OF ANY KIND, EXPRESSED OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE. SHOULD THE SOFTWARE PROVE DEFECTIVE
 * IN ANY RESPECT, THE USER ASSUMES THE COST OF ANY NECESSARY SERVICING, REPAIR OR
 * CORRECTION. THIS DISCLAIMER OF WARRANTY CONSTITUTES AN ESSENTIAL PART OF THIS LICENSE.
 * NO USE OF ANY SOFTWARE IS AUTHORIZED HEREUNDER EXCEPT UNDER THIS DISCLAIMER.
 * DESY HAS NO OBLIGATION TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS,
 * OR MODIFICATIONS.
 * THE FULL LICENSE SPECIFYING FOR THE SOFTWARE THE REDISTRIBUTION, MODIFICATION,
 * USAGE AND OTHER RIGHTS AND OBLIGATIONS IS INCLUDED WITH THE DISTRIBUTION OF THIS
 * PROJECT IN THE FILE LICENSE.HTML. IF THE LICENSE IS NOT INCLUDED YOU MAY FIND A COPY
 * AT HTTP://WWW.DESY.DE/LEGAL/LICENSE.HTM
 */
/*
 * $Id$
 */

package org.csstudio.ams.messageminder;

import org.csstudio.platform.logging.CentralLogger;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.remotercp.common.servicelauncher.ServiceLauncher;
import org.remotercp.ecf.ECFConstants;
import org.remotercp.login.connection.HeadlessConnection;

/**
 * @author hrickens
 * @author $Author$
 * @version $Revision$
 * @since 01.11.2007
 */
public final class MessageMinderStart implements IApplication {

    private boolean _restart = false;
    private boolean _run = true;
    private MessageGuardCommander _commander;
    private static MessageMinderStart _instance;
    public final static boolean CREATE_DURABLE = true;

    /* (non-Javadoc)
     * @see org.eclipse.equinox.app.IApplication#start(org.eclipse.equinox.app.IApplicationContext)
     */
    public Object start(IApplicationContext context) throws Exception {
        _instance = this;
        
        connectToXMPPServer();

        CentralLogger.getInstance().info(this, "MessageMinder started...");

        _commander = new MessageGuardCommander("MessageMinder");
        _commander.schedule();
        
        while(_commander.getState()!=Job.NONE){
            System.out.println(_commander.getState());
            Thread.sleep(10000);
        }
        _commander.cancel();
        if(_restart){
            return EXIT_RESTART;
        }else{
            return EXIT_OK;
        }
    }

    public void connectToXMPPServer()
    {
        String xmppUser = "ams-message-minder";
        String xmppPassword = "ams";
        String xmppServer = "krykxmpp.desy.de";

        try
        {
            HeadlessConnection.connect(xmppUser, xmppPassword, xmppServer, ECFConstants.XMPP);
            ServiceLauncher.startRemoteServices();     
        }
        catch(Exception e)
        {
            CentralLogger.getInstance().warn(this, "Could not connect to XMPP server: " + e.getMessage());
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.equinox.app.IApplication#stop()
     */
    public void stop() {
        // TODO Auto-generated method stub

    }

    public boolean isRestart() {
        return _restart;
    }

    public synchronized void setRestart() {
        _restart = true;
        setRun(false);
    }


    public synchronized void setRun(boolean run) {
        if(_commander!=null){
            _commander.setRun(run);
        }
    }

    public static MessageMinderStart getInstance() {
        return _instance;
    }

}
