/**
 * Local ({@link StringConnection}) network message system.
 * This file Copyright (C) 2007-2009,2016-2017,2020-2022 Jeremy D Monin <jeremy@nand.net>
 * Portions of this file Copyright (C) 2012 Paul Bilnoski <paul@bilnoski.net>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * The maintainer of this program can be reached at jsettlers@nand.net
 **/
package soc.server.genericServer;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

/**
 *
 * Clients who want to connect, call connectTo and are queued. (Thread.wait is used internally)
 * Server-side calls {@link #accept()} to retrieve them.
 *
 *<PRE>
 *  1.0.0 - 2007-11-18 - initial release, becoming part of jsettlers v1.1.00
 *  1.0.3 - 2008-08-08 - add change history; no other changes in this file since 1.0.0 (jsettlers 1.1.00 release)
 *  1.0.4 - 2008-09-04 - no change in this file
 *  1.0.5 - 2009-05-31 - no change in this file
 *  1.0.5.1- 2009-10-26- no change in this file
 *  2.0.0 - 2017-11-01 - Rename StringServerSocket -> SOCServerSocket, NetStringServerSocket -> NetServerSocket,
 *                       LocalStringServerSocket -> StringServerSocket. Remove unused broadcast(..).
 *  2.1.0 - 2020-01-09 - Only TCP-server changes: See {@link SOCServerSocket}
 *  2.3.0 - 2020-04-27 - no change in this file
 *  2.5.0 - 2021-12-30 - no change in this file
 *</PRE>
 *
 * @see NetServerSocket
 * @see StringConnection
 * @author Jeremy D Monin &lt;jeremy@nand.net&gt;
 * @since 1.1.00
 */
public class StringServerSocket implements SOCServerSocket
{
    protected static Hashtable<String, StringServerSocket> allSockets
        = new Hashtable<String, StringServerSocket>();

    /**
     * Length of queue for accepting new connections; default 100.
     * Changing it here affects future calls to connectTo() in all
     * instances.
     */
    public static int ACCEPT_QUEUELENGTH = 100;

    /** Server-peer sides of connected clients; Added by accept method */
    protected Vector<StringConnection> allConnected;

    /** Waiting client connections (client-peer sides); Added by connectClient, removed by accept method */
    protected Vector<StringConnection> acceptQueue;

    private String socketName;
    boolean out_setEOF;

    private Object sync_out_setEOF;  // For synchronized methods, so we don't sync on "this".

    public StringServerSocket(String name)
    {
        socketName = name;
        allConnected = new Vector<StringConnection>();
        acceptQueue = new Vector<StringConnection>();
        out_setEOF = false;
        sync_out_setEOF = new Object();
        allSockets.put(name, this);
    }

    /**
     * Find and connect to stringport with this name.
     * Intended to be called by client thread.
     * Will block-wait until the server calls accept().
     * Returns a new client connection after accept.
     *
     * @param name Stringport server name to connect to
     *
     * @throws ConnectException If stringport name is not found, or is EOF,
     *                          or if its connect/accept queue is full.
     * @throws IllegalArgumentException If name is null
     */
    public static StringConnection connectTo(String name)
        throws ConnectException, IllegalArgumentException
    {
        return connectTo (name, new StringConnection());
    }

    /**
     * Find and connect to stringport with this name.
     * Intended to be called by client thread.
     * Will block-wait until the server calls accept().
     *
     * @param name Stringport server name to connect to
     * @param client Existing unused connection object to connect with
     *
     * @throws ConnectException If stringport name is not found, or is EOF,
     *                          or if its connect/accept queue is full.
     * @throws IllegalArgumentException If name is null, client is null,
     *                          or client is already peered/connected.
     *
     * @return client parameter object, connected to a LocalStringServer
     */
    public static StringConnection connectTo(String name, StringConnection client)
        throws ConnectException, IllegalArgumentException
    {
        if (name == null)
            throw new IllegalArgumentException("name null");
        if (client == null)
            throw new IllegalArgumentException("client null");
        if (client.getPeer() != null)
            throw new IllegalArgumentException("client already peered");

        if (! allSockets.containsKey(name))
            throw new ConnectException("StringServerSocket name not found: " + name);

        StringServerSocket ss = allSockets.get(name);
        if (ss.isOutEOF())
            throw new ConnectException("StringServerSocket already EOF: " + name);

        StringConnection servSidePeer;
        try
        {
            servSidePeer = ss.queueAcceptClient(client);
        }
        catch (ConnectException ce)
        {
            throw ce;
        }
        catch (Throwable t)
        {
            ConnectException ce = new ConnectException("Error queueing to accept for " + name);
            ce.initCause(t);
            throw ce;
        }

        // Since we called queueAcceptClient, that server-side thread may have woken
        // and accepted the connection from this client-side thread already.
        // So, check if we're accepted, before waiting to be accepted.
        //
        synchronized (servSidePeer)
        {
            // Sync vs. critical section in accept

            if (! servSidePeer.isAccepted())
            {
                try
                {
                    servSidePeer.wait();  // Notified by accept method
                }
                catch (InterruptedException e) {}
            }
        }

        if (client != servSidePeer.getPeer())
            throw new IllegalStateException("Internal error: Peer is wrong");

        if (client.isOutEOF())
            throw new ConnectException("Server at EOF, closed waiting to be accepted");

        return client;
    }

    /**
     * Queue this client to be accepted, and return their new server-peer;
     * if calling this from methods initiated by the client, check if accepted.
     * If not accepted yet, call Thread.wait on the returned new peer object.
     * Once the server has accepted them, it will call Thread.notify on that object.
     *
     * @param client Client to queue to accept
     * @return peer Server-side peer of this client
     *
     * @throws IllegalStateException If we are at EOF already
     * @throws IllegalArgumentException If client is or was accepted somewhere already
     * @throws ConnectException If accept queue is full (ACCEPT_QUEUELENGTH)
     * @throws EOFException  If client is at EOF already
     *
     * @see #accept()
     * @see #ACCEPT_QUEUELENGTH
     */
    protected StringConnection queueAcceptClient(StringConnection client)
        throws IllegalStateException, IllegalArgumentException, ConnectException, EOFException
    {
        if (isOutEOF())
            throw new IllegalStateException("Internal error, already at EOF");
        if (client.isAccepted())
            throw new IllegalArgumentException("Client is already accepted somewhere");
        if (client.isOutEOF() || client.isInEOF())
            throw new EOFException("client is already at EOF");

        // Create server-side peer of client connect object, add client
        // to the accept queue, then notify any server thread waiting to
        // accept clients.  Accept() callers thread-wait on the newly
        // created peer object to prevent possible contention with other
        // objects; we know this new object won't have any locks on it.

        StringConnection serverPeer = new StringConnection(client);
        synchronized (acceptQueue)
        {
            if (acceptQueue.size() > ACCEPT_QUEUELENGTH)
                throw new ConnectException("Server accept queue is full");
            acceptQueue.add(client);
            acceptQueue.notifyAll();
        }

        return serverPeer;
    }

    /**
     * For server to call.  Blocks waiting for next inbound connection.
     * (Synchronizes on accept queue.)
     *
     * @return The server-side peer to the inbound client connection
     * @throws SocketException if our setEOF() has been called, thus
     *    new clients won't receive any data from us
     * @throws IOException if a network problem occurs (Which won't happen with this local communication)
     */
    public Connection accept() throws SocketException, IOException
    {
        if (out_setEOF)
            throw new SocketException("Server socket already at EOF");

        StringConnection cliPeer;

        synchronized (acceptQueue)
        {
            while (acceptQueue.isEmpty())
            {
                if (out_setEOF)
                    throw new SocketException("Server socket already at EOF");
                else
                {
                    try
                    {
                        acceptQueue.wait();  // Notified by queueAcceptClient
                    }
                    catch (InterruptedException e) {}
                }
            }
            cliPeer = acceptQueue.elementAt(0);
            acceptQueue.removeElementAt(0);
        }

        StringConnection servPeer = cliPeer.getPeer();
        cliPeer.setAccepted();

        if (out_setEOF)
        {
            servPeer.disconnect();
            cliPeer.disconnect();
        }

        synchronized (servPeer)
        {
            // Sync vs. critical section in connectTo;
            // client has been waiting there for our accept.

            if (! out_setEOF)
                servPeer.setAccepted();
            servPeer.notifyAll();
        }

        if (out_setEOF)
            throw new SocketException("Server socket already at EOF");

        allConnected.addElement(servPeer);

        return servPeer;
    }

    /**
     * @return Server-peer sides of all currently connected clients (StringConnections)
     */
    public Enumeration<StringConnection> allClients()
    {
        return allConnected.elements();
    }

    /**
     * If our server won't receive any more data from the client, disconnect them.
     * Considered EOF if the client's server-side peer connection inbound EOF is set.
     * Removes from allConnected and set outbound EOF flag on that connection.
     */
    public void disconnectEOFClients()
    {
        StringConnection servPeer;

        synchronized (allConnected)
        {
            for (int i = allConnected.size() - 1; i >= 0; --i)
            {
                servPeer = allConnected.elementAt(i);
                if (servPeer.isInEOF())
                {
                    allConnected.removeElementAt(i);
                    servPeer.setEOF();
                }
            }
        }
    }

    /**
     * @return Returns the socketName.
     */
    public String getSocketName()
    {
        return socketName;
    }

    /**
     * Close down server socket, but don't disconnect anyone:
     * Accept no new inbound connections.
     * Send EOF marker in all current outbound connections.
     * Continue to allow data from open inbound connections.
     *
     * @see #close()
     */
    public void setEOF()
    {
        setEOF(false);
    }

    /**
     * Close down server socket, possibly disconnect everyone;
     * For use by setEOF() and close().
     * Accept no new inbound connections.
     * Send EOF marker in all current outbound connections.
     *
     * @param forceDisconnect Call disconnect on clients, or just send them an EOF marker?
     *
     * @see #close()
     * @see StringConnection#disconnect()
     * @see StringConnection#setEOF()
     */
    protected void setEOF(boolean forceDisconnect)
    {
        synchronized (sync_out_setEOF)
        {
            out_setEOF = true;
        }

        Enumeration<StringConnection> connected = allConnected.elements();
        while (connected.hasMoreElements())
        {
            if (forceDisconnect)
                connected.nextElement().disconnect();
            else
                connected.nextElement().setEOF();
        }
    }

    /**
     * Have we closed our outbound side?
     *
     * @see #close()
     * @see #setEOF()
     */
    public boolean isOutEOF()
    {
        synchronized (sync_out_setEOF)
        {
            return out_setEOF;
        }
    }

    /**
     * Close down server socket immediately:
     * Do not let inbound data drain.
     * Accept no new inbound connections.
     * Send EOF marker in all current outbound connections.
     * Like java.net.ServerSocket, any thread currently blocked in
     * accept() will throw a SocketException.
     *
     * @see #setEOF()
     */
    public void close() throws IOException
    {
        setEOF(true);

        // Notify any threads waiting for accept.
        // In those threads, our connectTo method will see
        // the EOF and throw SocketException.
        for (StringConnection cliPeer : acceptQueue)
        {
            cliPeer.disconnect();
            synchronized (cliPeer)
            {
                cliPeer.notifyAll();
            }
        }
    }

}
