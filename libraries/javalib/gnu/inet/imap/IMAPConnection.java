/*
 * $Id: IMAPConnection.java,v 1.1 2004/07/25 22:46:22 dalibor Exp $
 * Copyright (C) 2003 The Free Software Foundation
 * 
 * This file is part of GNU inetlib, a library.
 * 
 * GNU inetlib is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * GNU inetlib is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 * 
 * As a special exception, if you link this library with other files to
 * produce an executable, this library does not by itself cause the
 * resulting executable to be covered by the GNU General Public License.
 * This exception does not however invalidate any other reasons why the
 * executable file might be covered by the GNU General Public License.
 */

package gnu.inet.imap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import javax.security.auth.callback.CallbackHandler;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;

import gnu.inet.util.BASE64;
import gnu.inet.util.CRLFOutputStream;
import gnu.inet.util.EmptyX509TrustManager;
import gnu.inet.util.Logger;
import gnu.inet.util.SaslCallbackHandler;
import gnu.inet.util.SaslInputStream;
import gnu.inet.util.SaslOutputStream;

/**
 * The protocol class implementing IMAP4rev1.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @version $Revision: 1.1 $ $Date: 2004/07/25 22:46:22 $
 */
public class IMAPConnection
implements IMAPConstants
{

  /**
   * Prefix for tags.
   */
  protected static final String TAG_PREFIX = "A";

  /**
   * The encoding used to create strings for IMAP commands.
   */
  protected static final String US_ASCII = "US-ASCII";

  /**
   * The default IMAP port.
   */
  protected static final int DEFAULT_PORT = 143;

  /**
   * The default IMAP-SSL port.
   */
  protected static final int DEFAULT_SSL_PORT = 993;

  /**
   * The socket used for communication with the server.
   */
  protected Socket socket;

  /**
   * The tokenizer used to read IMAP responses from.
   */
  protected IMAPResponseTokenizer in;

  /**
   * The output stream.
   */
  protected CRLFOutputStream out;

  /**
   * List of responses received asynchronously.
   */
  protected List asyncResponses;

  /**
   * List of alert strings.
   */
  private List alerts;

  /*
   * Used to generate new tags for tagged commands.
   */
  private int tagIndex = 0;

  /*
   * Print debugging output to stderr.
   */
  private boolean debug;

  /*
   * Print debugging output using ANSI colour escape sequences.
   */
  private boolean ansiDebug = false;

  /**
   * Creates a new connection.
   * @param host the name of the host to connect to
   * @param port the port to connect to, or -1 for the default
   */
  public IMAPConnection (String host, int port)
    throws UnknownHostException, IOException
    {
      this (host, port, -1, -1, false, null, false);
    }
  
  /**
   * Creates a new connection.
   * @param host the name of the host to connect to
   * @param port the port to connect to, or -1 for the default
   * @param connectionTimeout the socket connection timeout
   * @param timeout the socket timeout
   * @param debug log debugging information
   */
  public IMAPConnection (String host, int port,
                         int connectionTimeout, int timeout,
                         boolean debug)
    throws UnknownHostException, IOException
    {
      this (host, port, connectionTimeout, timeout, false, null, debug);
    }
  
  /**
   * Creates a new secure connection using the specified trust manager.
   * @param host the name of the host to connect to
   * @param port the port to connect to, or -1 for the default
   * @param tm a trust manager used to check SSL certificates, or null to
   * use the default
   */
  public IMAPConnection (String host, int port, TrustManager tm)
    throws UnknownHostException, IOException
    {
      this (host, port, -1, -1, true, tm, false);
    }
  
  /**
   * Creates a new connection.
   * @param host the name of the host to connect to
   * @param port the port to connect to, or -1 for the default
   * @param connectionTimeout the socket connection timeout
   * @param timeout the socket timeout
   * @param secure if an IMAP-SSL connection should be made
   * @param tm a trust manager used to check SSL certificates, or null to
   * use the default
   * @param debug log debugging information
   */
  public IMAPConnection (String host, int port,
                         int connectionTimeout, int timeout,
                         boolean secure, TrustManager tm,
                         boolean debug)
    throws UnknownHostException, IOException
    {
      this.debug = debug;
      // TODO connectionTimeout
      
      if (port < 0)
        {
          port = secure ? DEFAULT_SSL_PORT : DEFAULT_PORT;
        }
      
      // Set up socket
      try
        {
          socket = new Socket (host, port);
          if (timeout > 0)
            {
              socket.setSoTimeout (timeout);
            }
          if (secure)
            {
              SSLSocketFactory factory = getSSLSocketFactory (tm);
              SSLSocket ss =
                (SSLSocket) factory.createSocket (socket, host, port, true);
              String[] protocols = { "TLSv1", "SSLv3" };
              ss.setEnabledProtocols (protocols);
              ss.setUseClientMode (true);
              ss.startHandshake ();
              socket = ss;
            }
        }
      catch (GeneralSecurityException e)
        {
          e.printStackTrace ();
          throw new IOException (e.getMessage ());
        }
      
      InputStream in = socket.getInputStream ();
      in = new BufferedInputStream (in);
      this.in = new IMAPResponseTokenizer (in);
      OutputStream out = socket.getOutputStream ();
      out = new BufferedOutputStream (out);
      this.out = new CRLFOutputStream (out);
      
      asyncResponses = new ArrayList ();
      alerts = new ArrayList ();
    }
  
  /**
   * Sets whether debugging output should use ANSI colour escape sequences.
   */
  public void setAnsiDebug (boolean flag)
    {
      ansiDebug = flag;
    }
  
  /**
   * Returns a new tag for a command.
   */
  protected String newTag ()
    {
      return new StringBuffer (TAG_PREFIX)
        .append (++tagIndex)
        .toString();
    }
  
  /**
   * Sends the specified IMAP tagged command to the server.
   */
  protected void sendCommand (String tag, String command) throws IOException
    {
      if (debug)
        {
          Logger logger = Logger.getInstance ();
          logger.log("imap", "> " + tag + " " + command);
        }
      String cmd = new StringBuffer (tag)
        .append (' ')
        .append (command)
        .toString ();
      out.write (cmd);
      out.writeln ();
      out.flush ();
    }
  
  /**
   * Sends the specified IMAP command.
   * @param command the command
   * @return true if OK was received, or false if NO was received
   * @exception IOException if BAD was received or an I/O error occurred
   */
  protected boolean invokeSimpleCommand (String command) throws IOException
    {
      String tag = newTag ();
      sendCommand (tag, command);
      while (true)
        {
          IMAPResponse response = readResponse ();
          String id = response.getID ();
          if (tag.equals (response.getTag ()))
            {
              processAlerts (response);
              if (id == OK)
                {
                  return true;
                }
              else if (id == NO)
                {
                  return false;
                }
              else
                {
                  throw new IMAPException (id, response.getText ());
                }
            }
          else if (response.isUntagged ())
            {
              asyncResponses.add (response);
            }
          else
            {
              throw new IMAPException (id, response.getText ());
            }
        }
    }
  
  
  /**
   * Reads an IMAP response from the server.
   * The response will consist of <i>either</i>:
   * <ul>
   * <li>A tagged response corresponding to a pending command</li>
   * <li>An untagged error response</li>
   * <li>A continuation response</li>
   */
  protected IMAPResponse readResponse() throws IOException
    {
      IMAPResponse response = in.next();
      if (debug)
        {
          Logger logger = Logger.getInstance();
          if (response == null)
            {
              logger.log("imap", "<EOF");
            }
          else if (ansiDebug)
            {
              logger.log("imap", "< " + response.toANSIString());
            }
          else
            {
              logger.log("imap", "< " + response.toString());
            }
        }
      if (response == null)
        {
          throw new IOException("EOF");
        }
      return response;
    }
  
  // -- Alert notifications --
  
  private void processAlerts (IMAPResponse response)
    {
      List code = response.getResponseCode ();
      if (code != null && code.contains (ALERT))
        {
          alerts.add (response.getText ());
        }
    }
  
  /**
   * Indicates if there are alerts pending for the user-agent.
   */
  public boolean alertsPending ()
    {
      return (alerts.size () > 0);
    }
  
  /**
   * Returns the pending alerts for the user-agent as an array.
   */
  public String[] getAlerts ()
    {
      String[] a = new String[alerts.size ()];
      alerts.toArray (a);
      alerts.clear ();             // flush
      return a;
    }
  
  // -- IMAP commands --
  
  /**
   * Returns a list of the capabilities of the IMAP server.
   */
  public List capability () throws IOException
    {
      String tag = newTag ();
      sendCommand (tag, CAPABILITY);
      List capabilities = new ArrayList ();
      while (true)
        {
          IMAPResponse response = readResponse ();
          String id = response.getID ();
          if (tag.equals (response.getTag ()))
            {
              processAlerts (response);
              if (id == OK)
                {
                  if (capabilities.size () == 0)
                    {
                      // The capability list may be contained in the
                      // response text.
                      addTokens (capabilities, response.getText ());
                    }
                  return capabilities;
                }
              else
                {
                  throw new IMAPException (id, response.getText ());
                }
            }
          else if (response.isUntagged ())
            {
              if (id == CAPABILITY)
                {
                  // The capability list may be contained in the
                  // response text.
                  addTokens (capabilities, response.getText ());
                }
              else if (id == OK)
                {
                  // The capability list may be contained in the
                  // response code.
                  List code = response.getResponseCode ();
                  int len = (code == null) ? 0 : code.size ();
                  if (len > 0 && CAPABILITY.equals (code.get (0)))
                    {
                      for (int i = 1; i < len; i++)
                        {
                          String token = (String) code.get (i);
                          if (!capabilities.contains (token))
                            capabilities.add (token);
                        }
                    }
                  else
                    {
                      asyncResponses.add (response);
                    }
                }
              else
                {
                  asyncResponses.add (response);
                }
            }
          else
            {
              throw new IMAPException (id, response.getText ());
            }
        }
    }
  
  private void addTokens (List list, String text)
    {
      int start = 0;
      int end = text.indexOf (' ');
      String token;
      while (end != -1)
        {
          token = text.substring (start, end);
          if (!list.contains (token))
            {
              list.add (token);
            }
          start = end + 1;
          end = text.indexOf (' ', start);
        }
      token = text.substring (start);
      if (token.length () > 0 && !list.contains (token))
        {
          list.add (token);
        }
    }
  
  /**
   * Ping the server.
   * If a change in mailbox state is detected, a new mailbox status is
   * returned, otherwise this method returns null.
   */
  public MailboxStatus noop () throws IOException
    {
      String tag = newTag ();
      sendCommand (tag, NOOP);
      boolean changed = false;
      MailboxStatus ms = new MailboxStatus ();
      Iterator asyncIterator = asyncResponses.iterator ();
      while (true)
        {
          IMAPResponse response;
          // Process any asynchronous responses first
          if (asyncIterator.hasNext ())
            {
              response = (IMAPResponse) asyncIterator.next ();
              asyncIterator.remove ();
            }
          else
            {
              response = readResponse ();
            }
          String id = response.getID ();
          if (response.isUntagged ())
            {
              changed = changed || updateMailboxStatus (ms, id, response);
            }
          else if (tag.equals (response.getTag ()))
            {
              processAlerts (response);
              if (id == OK)
                {
                  return changed ? ms : null;
                }
              else
                {
                  throw new IMAPException (id, response.getText ());
                }
            }
          else
            {
              throw new IMAPException (id, response.getText ());
            }
        }
    }

  private SSLSocketFactory getSSLSocketFactory (TrustManager tm)
    throws GeneralSecurityException
  {
    if (tm == null)
      {
        tm = new EmptyX509TrustManager ();
      }
    SSLContext context = SSLContext.getInstance ("TLS");
    TrustManager[] trust = new TrustManager[] { tm };
    context.init (null, trust, null);
    return context.getSocketFactory ();
  }
  
  /**
   * Attempts to start TLS on the specified connection.
   * See RFC 2595 for details.
   * @return true if successful, false otherwise
   */
  public boolean starttls () throws IOException
    {
      return starttls (new EmptyX509TrustManager ());
    }
  
  /**
   * Attempts to start TLS on the specified connection.
   * See RFC 2595 for details.
   * @param tm the custom trust manager to use
   * @return true if successful, false otherwise
   */
  public boolean starttls (TrustManager tm) throws IOException
    {
      try
        {
          SSLSocketFactory factory = getSSLSocketFactory (tm);
          String hostname = socket.getInetAddress ().getHostName ();
          int port = socket.getPort ();
          
          String tag = newTag ();
          sendCommand (tag, STARTTLS);
          while (true)
            {
              IMAPResponse response = readResponse ();
              if (response.isTagged () && tag.equals (response.getTag ()))
                {
                  processAlerts (response);
                  String id = response.getID ();
                  if (id == OK)
                    {
                      break;              // negotiate TLS
                    }
                  else if (id == BAD)
                    {
                      return false;
                    }
                }
              else
                {
                  asyncResponses.add (response);
                }
            }

          SSLSocket ss =
            (SSLSocket) factory.createSocket (socket, hostname, port, true);
          String[] protocols = { "TLSv1", "SSLv3" };
          ss.setEnabledProtocols (protocols);
          ss.setUseClientMode (true);
          ss.startHandshake ();
          
          InputStream in = ss.getInputStream ();
          in = new BufferedInputStream (in);
          this.in = new IMAPResponseTokenizer (in);
          OutputStream out = ss.getOutputStream ();
          out = new BufferedOutputStream (out);
          this.out = new CRLFOutputStream (out);
          return true;
        }
      catch (GeneralSecurityException e)
        {
          e.printStackTrace();
          return false;
        }
    }

  /**
   * Login to the connection using the username and password method.
   * @param username the authentication principal
   * @param password the authentication credentials
   * @return true if authentication was successful, false otherwise
   */
  public boolean login(String username, String password) throws IOException
    {
      StringBuffer cmd = new StringBuffer(LOGIN);
      cmd.append(' ');
      cmd.append(quote(username));
      cmd.append(' ');
      cmd.append(quote(password));
      return invokeSimpleCommand(cmd.toString());
    }
  
  /**
   * Authenticates the connection using the specified SASL mechanism,
   * username, and password.
   * @param mechanism a SASL authentication mechanism, e.g. LOGIN, PLAIN,
   * CRAM-MD5, GSSAPI
   * @param username the authentication principal
   * @param password the authentication credentials
   * @return true if authentication was successful, false otherwise
   */
  public boolean authenticate(String mechanism, String username,
                              String password) throws IOException
    {
      try
        {
          String[] m = new String[] { mechanism };
          CallbackHandler ch = new SaslCallbackHandler(username, password);
          // Avoid lengthy callback procedure for GNU Crypto
          Properties p = new Properties();
          p.put("gnu.crypto.sasl.username", username);
          p.put("gnu.crypto.sasl.password", password);
          SaslClient sasl = Sasl.createSaslClient(m, null, "smtp",
                                                  socket.getInetAddress().
                                                  getHostName(), p, ch);
          if (sasl == null)
            return false;
          
          StringBuffer cmd = new StringBuffer(AUTHENTICATE);
          cmd.append(' ');
          cmd.append(mechanism);
          if (sasl.hasInitialResponse())
            {
              cmd.append(' ');
              byte[] init = sasl.evaluateChallenge(new byte[0]);
              cmd.append(new String(init, US_ASCII));
            }
          String tag = newTag();
          sendCommand(tag, cmd.toString());
          while (true)
            {
              IMAPResponse response = readResponse();
              if (tag.equals(response.getTag()))
                {
                  processAlerts(response);
                  String id = response.getID();
                  if (id == OK)
                    {
                      String qop =
                        (String) sasl.getNegotiatedProperty(Sasl.QOP);
                      if ("auth-int".equalsIgnoreCase(qop)
                          || "auth-conf".equalsIgnoreCase(qop))
                        {
                          InputStream in = socket.getInputStream();
                          in = new BufferedInputStream(in);
                          in = new SaslInputStream(sasl, in);
                          this.in = new IMAPResponseTokenizer(in);
                          OutputStream out = socket.getOutputStream();
                          out = new BufferedOutputStream(out);
                          out = new SaslOutputStream(sasl, out);
                          this.out = new CRLFOutputStream(out);
                        }
                      return true;
                    }
                  else if (id == NO)
                    return false;
                  else if (id == BAD)
                    throw new IMAPException(id, response.getText());
                }
              else if (response.isContinuation())
                {
                  try
                    {
                      byte[] c0 = response.getText().getBytes(US_ASCII);
                      byte[] c1 = BASE64.decode(c0);       // challenge
                      byte[] r0 = sasl.evaluateChallenge(c1);
                      byte[] r1 = BASE64.encode(r0);       // response
                      out.write(r1);
                      out.writeln();
                      out.flush();
                    }
                  catch(SaslException e)
                    {
                      // Error in SASL challenge evaluation - cancel exchange
                      out.write(0x2a);
                      out.writeln();
                      out.flush();
                    }
                }
              else
                asyncResponses.add(response);
            }
        }
      catch (SaslException e)
        {
          return false;             // No provider for mechanism
        }
      catch (RuntimeException e)
        {
          return false;             // No javax.security.sasl classes
        }
    }
  
  /**
   * Logout this connection.
   * Underlying network resources will be freed.
   */
  public void logout() throws IOException
    {
      String tag = newTag();
      sendCommand(tag, LOGOUT);
      while (true)
        {
          IMAPResponse response = readResponse();
          if (response.isTagged() && tag.equals(response.getTag()))
            {
              processAlerts(response);
              String id = response.getID();
              if (id == OK)
                {
                  socket.close();
                  return;
                }
              else
                throw new IMAPException(id, response.getText());
            }
          else
            asyncResponses.add(response);
        }
    }
  
  /**
   * Selects the specified mailbox.
   * The mailbox is identified as read-write if writes are permitted.
   * @param mailbox the mailbox name
   * @return a MailboxStatus containing the state of the selected mailbox
   */
  public MailboxStatus select(String mailbox) throws IOException
    {
      return selectImpl(mailbox, SELECT);
    }
  
  /**
   * Selects the specified mailbox.
   * The mailbox is identified as read-only.
   * @param mailbox the mailbox name
   * @return a MailboxStatus containing the state of the selected mailbox
   */
  public MailboxStatus examine(String mailbox) throws IOException
    {
      return selectImpl(mailbox, EXAMINE);
    }

  protected MailboxStatus selectImpl(String mailbox, String command)
    throws IOException
    {
      String tag = newTag();
      sendCommand(tag,
                  new StringBuffer(command).append(' ').
                  append(quote(UTF7imap.encode(mailbox))).toString());
      MailboxStatus ms = new MailboxStatus();
      while (true)
        {
          IMAPResponse response = readResponse();
          String id = response.getID();
          if (response.isUntagged())
            {
              if (!updateMailboxStatus(ms, id, response))
                asyncResponses.add(response);
            }
          else if (tag.equals(response.getTag()))
            {
              processAlerts(response);
              if (id == OK)
                {
                  List rc = response.getResponseCode();
                  if (rc.size() > 0 && rc.get(0) == READ_WRITE)
                    ms.readWrite = true;
                  return ms;
                }
              else
                throw new IMAPException(id, response.getText());
            }
          else
            throw new IMAPException(id, response.getText());
        }
    }
  
  protected boolean updateMailboxStatus(MailboxStatus ms,
                                        String id, IMAPResponse response)
    throws IOException
    {
      if (id == OK)
        {
          boolean changed = false;
          List rc = response.getResponseCode();
          int len = (rc == null) ? 0 : rc.size();
          for (int i = 0; i < len; i++)
            {
              Object ocmd = rc.get(i);
              if (ocmd instanceof String)
                {
                  String cmd = (String) ocmd;
                  if (i + 1 < len)
                    {
                      Object oparam = rc.get(i + 1);
                      if (oparam instanceof String)
                        {
                          String param = (String) oparam;
                          try
                            {
                              if (cmd == UNSEEN)
                                {
                                  ms.firstUnreadMessage =
                                    Integer.parseInt(param);
                                  i++;
                                  changed = true;
                                }
                              else if (cmd == UIDVALIDITY)
                                {
                                  ms.uidValidity = Integer.parseInt(param);
                                  i++;
                                  changed = true;
                                }
                            }
                          catch(NumberFormatException e)
                            {
                              throw new ProtocolException("Illegal " + cmd +
                                                          " value: " + param);
                            }
                        }
                      else if (oparam instanceof List)
                        {
                          if (cmd == PERMANENTFLAGS)
                            {
                              ms.permanentFlags = (List) oparam;
                              i++;
                              changed = true;
                            }
                        }
                    }
                }
            }
          return changed;
        }
      else if (id == EXISTS)
        {
          ms.messageCount = response.getCount();
          return true;
        }
      else if (id == RECENT)
        {
          ms.newMessageCount = response.getCount();
          return true;
        }
      else if (id == FLAGS)
        {
          ms.flags = response.getResponseCode();
          return true;
        }
      else
        return false;
    }
  
  /**
   * Creates a mailbox with the specified name.
   * @param mailbox the mailbox name
   * @return true if the mailbox was successfully created, false otherwise
   */
  public boolean create(String mailbox) throws IOException
    {
      return invokeSimpleCommand(new StringBuffer(CREATE).append(' ').
                                 append(quote(UTF7imap.encode(mailbox))).
                                 toString());
    }
  
  /**
   * Deletes the mailbox with the specified name.
   * @param mailbox the mailbox name
   * @return true if the mailbox was successfully deleted, false otherwise
   */
  public boolean delete(String mailbox) throws IOException
    {
      return invokeSimpleCommand(new StringBuffer(DELETE).append(' ').
                                 append(quote(UTF7imap.encode(mailbox))).
                                 toString());
    }
  
  /**
   * Renames the source mailbox to the specified name.
   * @param source the source mailbox name
   * @param target the target mailbox name
   * @return true if the mailbox was successfully renamed, false otherwise
   */
  public boolean rename(String source, String target) throws IOException
    {
      return invokeSimpleCommand(new StringBuffer(RENAME).append(' ').
                                 append(quote(UTF7imap.encode(source))).
                                 append(' ').
                                 append(quote(UTF7imap.encode(target))).
                                 toString());
    }
  
  /**
   * Adds the specified mailbox to the set of subscribed mailboxes as
   * returned by the LSUB command.
   * @param mailbox the mailbox name
   * @return true if the mailbox was successfully subscribed, false otherwise
   */
  public boolean subscribe(String mailbox) throws IOException
    {
      return invokeSimpleCommand(new StringBuffer(SUBSCRIBE).append(' ').
                                 append(quote(UTF7imap.encode(mailbox))).
                                 toString());
    }
  
  /**
   * Removes the specified mailbox from the set of subscribed mailboxes as
   * returned by the LSUB command.
   * @param mailbox the mailbox name
   * @return true if the mailbox was successfully unsubscribed, false otherwise
   */
  public boolean unsubscribe(String mailbox) throws IOException
    {
      return invokeSimpleCommand(new StringBuffer(UNSUBSCRIBE).append(' ').
                                 append(quote(UTF7imap.encode(mailbox))).
                                 toString());
    }
  
  /**
   * Returns a subset of names from the compete set of names available to
   * the client.
   * @param reference the context relative to which mailbox names are
   * defined
   * @param mailbox a mailbox name, possibly including IMAP wildcards
   */
  public ListEntry[] list(String reference, String mailbox) throws IOException
    {
      return listImpl(LIST, reference, mailbox);
    }
  
  /**
   * Returns a subset of subscribed names.
   * @see #list
   */
  public ListEntry[] lsub(String reference, String mailbox) throws IOException
    {
      return listImpl(LSUB, reference, mailbox);
    }
  
  protected ListEntry[] listImpl(String command, String reference,
                                 String mailbox) throws IOException
    {
      if (reference == null)
        reference = "";
      if (mailbox == null)
        mailbox = "";
      String tag = newTag();
      sendCommand(tag,
                  new StringBuffer(command).append(' ').
                  append(quote(UTF7imap.encode(reference))).append(' ').
                  append(quote(UTF7imap.encode(mailbox))).toString());
      List acc = new ArrayList();
      while (true)
        {
          IMAPResponse response = readResponse();
          String id = response.getID();
          if (response.isUntagged())
            {
              if (id.equals(command))
                {
                  List code = response.getResponseCode();
                  String text = response.getText();
                  
                  // Populate entry attributes with the interned versions
                  // of the response code.
                  // NB IMAP servers do not necessarily pay attention to case.
                  int alen = code.size();
                  boolean noinferiors = false;
                  boolean noselect = false;
                  boolean marked = false;
                  boolean unmarked = false;
                  for (int i = 0; i < alen; i++)
                    {
                      String attribute = (String) code.get(i);
                      if (attribute.equalsIgnoreCase(LIST_NOINFERIORS))
                        noinferiors = true;
                      else if (attribute.equalsIgnoreCase(LIST_NOSELECT))
                        noselect = true;
                      else if (attribute.equalsIgnoreCase(LIST_MARKED))
                        marked = true;
                      else if (attribute.equalsIgnoreCase(LIST_UNMARKED))
                        unmarked = true;
                    }
                  int si = text.indexOf(' ');
                  char delimiter = '\u0000';
                  String d = text.substring(0, si);
                  if (!d.equalsIgnoreCase(NIL))
                    delimiter = stripQuotes(d).charAt(0);
                  String mbox = stripQuotes(text.substring(si + 1));
                  mbox = UTF7imap.decode(mbox);
                  ListEntry entry = new ListEntry(mbox, delimiter, noinferiors,
                                                  noselect, marked, unmarked);
                  acc.add(entry);
                }
              else
                asyncResponses.add(response);
            }
          else if (tag.equals(response.getTag()))
            {
              processAlerts(response);
              if (id == OK)
                {
                  ListEntry[] entries = new ListEntry[acc.size()];
                  acc.toArray(entries);
                  return entries;
                }
              else
                throw new IMAPException(id, response.getText());
            }
          else
            throw new IMAPException(id, response.getText());
        }
    }
  
  /**
   * Requests the status of the specified mailbox.
   */
  public MailboxStatus status(String mailbox, String[] statusNames)
    throws IOException
    {
      String tag = newTag();
      StringBuffer buffer =
        new StringBuffer(STATUS).append(' ').
        append(quote(UTF7imap.encode(mailbox))).append(' ').append('(');
      for (int i = 0; i < statusNames.length; i++)
        {
          if (i > 0)
            buffer.append(' ');
          buffer.append(statusNames[i]);
        }
      buffer.append(')');
      sendCommand(tag, buffer.toString());
      MailboxStatus ms = new MailboxStatus();
      while (true)
        {
          IMAPResponse response = readResponse();
          String id = response.getID();
          if (response.isUntagged())
            {
              if (id == STATUS)
                {
                  List code = response.getResponseCode();
                  int last = code.size() - 1;
                  for (int i = 0; i < last; i += 2)
                    {
                      try
                        {
                          String statusName = ((String) code.get(i)).intern();
                          int value = Integer.parseInt((String) code.get(i + 1));
                          if (statusName == MESSAGES)
                            ms.messageCount = value;
                          else if (statusName == RECENT)
                            ms.newMessageCount = value;
                          else if (statusName == UIDNEXT)
                            ms.uidNext = value;
                          else if (statusName == UIDVALIDITY)
                            ms.uidValidity = value;
                          else if (statusName == UNSEEN)
                            ms.firstUnreadMessage = value;
                        }
                      catch(NumberFormatException e)
                        {
                          throw new IMAPException(id, "Invalid code: " + code);
                        }
                    }
                }
              else
                asyncResponses.add(response);
            }
          else if (tag.equals(response.getTag()))
            {
              processAlerts(response);
              if (id == OK)
                return ms;
              else
                throw new IMAPException(id, response.getText());
            }
          else
            throw new IMAPException(id, response.getText());
        }
    }
  
  /**
   * Append a message to the specified mailbox.
   * This method returns an OutputStream to which the message should be
   * written and then closed.
   * @param mailbox the mailbox name
   * @param flags optional list of flags to specify for the message
   * @param content the message body (including headers)
   * @return true if successful, false if error in flags/text
   */
  public boolean append(String mailbox, String[] flags, byte[] content)
    throws IOException
    {
      String tag = newTag();
      StringBuffer buffer =
        new StringBuffer(APPEND).append(' ').
        append(quote(UTF7imap.encode(mailbox))).append(' ');
      if (flags != null)
        {
          buffer.append('(');
          for (int i = 0; i < flags.length; i++)
            {
              if (i > 0)
                buffer.append(' ');
              buffer.append(flags[i]);
            }
          buffer.append(')');
          buffer.append(' ');
        }
      buffer.append('{');
      buffer.append(content.length);
      buffer.append('}');
      sendCommand(tag, buffer.toString());
      IMAPResponse response = readResponse();
      if (!response.isContinuation())
        throw new IMAPException(response.getID(), response.getText());
      out.write(content);         // write the message body
      out.writeln();
      out.flush();
      while (true)
        {
          response = readResponse();
          String id = response.getID();
          if (tag.equals(response.getTag()))
            {
              processAlerts(response);
              if (id == OK)
                return true;
              else if (id == NO)
                return false;
              else
                throw new IMAPException(id, response.getText());
            }
          else if (response.isUntagged())
            asyncResponses.add(response);
          else
            throw new IMAPException(id, response.getText());
        }
    }
  
  /**
   * Request a checkpoint of the currently selected mailbox.
   */
  public void check() throws IOException
    {
      invokeSimpleCommand(CHECK);
    }
  
  /**
   * Permanently remove all messages that have the \Deleted flags set,
   * and close the mailbox.
   * @return true if successful, false if no mailbox was selected
   */
  public boolean close() throws IOException
    {
      return invokeSimpleCommand(CLOSE);
    }
  
  /**
   * Permanently removes all messages that have the \Delete flag set.
   * @return the numbers of the messages expunged
   */
  public int[] expunge() throws IOException
    {
      String tag = newTag();
      sendCommand(tag, EXPUNGE);
      List numbers = new ArrayList();
      while (true)
        {
          IMAPResponse response = readResponse();
          String id = response.getID();
          if (response.isUntagged())
            {
              if (id == EXPUNGE)
                numbers.add(new Integer(response.getCount()));
              else
                asyncResponses.add(response);
            }
          else if (tag.equals(response.getTag()))
            {
              processAlerts(response);
              if (id == OK)
                {
                  int len = numbers.size();
                  int[] mn = new int[len];
                  for (int i = 0; i < len; i++)
                    mn[i] = ((Integer) numbers.get(i)).intValue();
                  return mn;
                }
              else
                throw new IMAPException(id, response.getText());
            }
          else
            throw new IMAPException(id, response.getText());
        }
    }
  
  /**
   * Searches the currently selected mailbox for messages matching the
   * specified criteria.
   */
  public int[] search(String charset, String[] criteria) throws IOException
    {
      String tag = newTag();
      StringBuffer buffer = new StringBuffer(SEARCH);
      buffer.append(' ');
      if (charset != null)
        {
      buffer.append(charset);
      buffer.append(' ');
        }
      for (int i = 0; i < criteria.length; i++)
        {
          if (i > 0)
            buffer.append(' ');
          buffer.append(criteria[i]);
        }
      sendCommand(tag, buffer.toString());
      List list = new ArrayList();
      while (true)
        {
          IMAPResponse response = readResponse();
          String id = response.getID();
          if (response.isUntagged())
            {
              if (id == SEARCH)
                {
                  String text = response.getText();
                  try
                    {
                      int si = text.indexOf(' ');
                      while (si != -1)
                        {
                          list.add(new Integer(text.substring(0, si)));
                          text = text.substring(si + 1);
                          si = text.indexOf(' ');
                        }
                      list.add(new Integer(text));
                    }
                  catch(NumberFormatException e)
                    {
                      throw new IMAPException(id, "Expecting number: " + text);
                    }
                }
              else
                asyncResponses.add(response);
            }
          else if (tag.equals(response.getTag()))
            {
              processAlerts(response);
              if (id == OK)
                {
                  int len = list.size();
                  int[] mn = new int[len];
                  for (int i = 0; i < len; i++)
                    mn[i] = ((Integer) list.get(i)).intValue();
                  return mn;
                }
              else
                throw new IMAPException(id, response.getText());
            }
          else
            throw new IMAPException(id, response.getText());
        }
    }
  
  /**
   * Retrieves data associated with messages in the mailbox.
   * @param messages the message numbers
   */
  public MessageStatus[] fetch(int[] messages, String[] fetchCommands)
    throws IOException
    {
      String tag = newTag();
      StringBuffer buffer = new StringBuffer(FETCH);
      buffer.append(' ');
      for (int i = 0; i < messages.length; i++)
        {
          if (i > 0)
            buffer.append(',');
          buffer.append(messages[i]);
        }
      buffer.append(' ');
      buffer.append('(');
      for (int i = 0; i < fetchCommands.length; i++)
        {
          if (i > 0)
            buffer.append(' ');
          buffer.append(fetchCommands[i]);
        }
      buffer.append(')');
      sendCommand(tag, buffer.toString());
      List list = new ArrayList(messages.length);
      while (true)
        {
          IMAPResponse response = readResponse();
          String id = response.getID();
          if (response.isUntagged())
            {
              if (id == FETCH)
                {
                  int msgnum = response.getCount();
                  List code = response.getResponseCode();
                  MessageStatus status = new MessageStatus(msgnum, code);
                  list.add(status);
                }
              else
                asyncResponses.add(response);
            }
          else if (tag.equals(response.getTag()))
            {
              processAlerts(response);
              if (id == OK)
                {
                  MessageStatus[] statuses = new MessageStatus[list.size()];
                  list.toArray(statuses);
                  return statuses;
                }
              else
                throw new IMAPException(id, response.getText());
            }
          else
            throw new IMAPException(id, response.getText());
        }
    }
  
  /**
   * Alters data associated with a message in the mailbox.
   * @param messages the message numbers
   * @param flagCommand FLAGS, +FLAGS, -FLAGS (or .SILENT versions)
   * @param flags message flags to set
   * @return a list of message-number to current flags
   */
  public MessageStatus[] store(int[] messages,
                               String flagCommand,
                               String[] flags) throws IOException
    {
      String tag = newTag();
      StringBuffer buffer = new StringBuffer(STORE);
      buffer.append(' ');
      for (int i = 0; i < messages.length; i++)
        {
          if (i > 0)
            buffer.append(',');
          buffer.append(messages[i]);
        }
      buffer.append(' ');
      buffer.append(flagCommand);
      buffer.append(' ');
      buffer.append('(');
      for (int i = 0; i < flags.length; i++)
        {
          if (i > 0)
            buffer.append(' ');
          buffer.append(flags[i]);
        }
      buffer.append(')');
      sendCommand(tag, buffer.toString());
      List list = new ArrayList(messages.length);
      while (true)
        {
          IMAPResponse response = readResponse();
          String id = response.getID();
          if (response.isUntagged())
            {
              int msgnum = response.getCount();
              List code = response.getResponseCode();
              // 2 different styles returned by server: FETCH or FETCH FLAGS
              if (id == FETCH)
                {
                  MessageStatus mf = new MessageStatus(msgnum, code);
                  list.add(mf);
                }
              else if (id == FETCH_FLAGS)
                {
                  List base = new ArrayList();
                  base.add(FLAGS);
                  base.add(code);
                  MessageStatus mf = new MessageStatus(msgnum, base);
                  list.add(mf);
                }
              else
                asyncResponses.add(response);
            }
          else if (tag.equals(response.getTag()))
            {
              processAlerts(response);
              if (id == OK)
                {
                  MessageStatus[] mf = new MessageStatus[list.size()];
                  list.toArray(mf);
                  return mf;
                }
              else
                throw new IMAPException(id, response.getText());
            }
          else
            throw new IMAPException(id, response.getText());
        }
    }
  
  /**
   * Copies the specified messages to the end of the destination mailbox.
   * @param messages the message numbers
   * @param mailbox the destination mailbox
   */
  public boolean copy(int[] messages, String mailbox) throws IOException
    {
      if (messages == null || messages.length < 1)
        return true;
      StringBuffer buffer = new StringBuffer(COPY).append(' ');
      for (int i = 0; i < messages.length; i++)
        {
          if (i > 0)
            buffer.append(',');
          buffer.append(messages[i]);
        }
      buffer.append(' ').append(quote(UTF7imap.encode(mailbox)));
      return invokeSimpleCommand(buffer.toString());
    }
  
  // -- Utility methods --
  
  /**
   * Remove the quotes from each end of a string literal.
   */
  static String stripQuotes(String text)
    {
      if (text.charAt(0) == '"')
        {
          int len = text.length();
          if (text.charAt(len - 1) == '"')
            return text.substring(1, len - 1);
        }
      return text;
    }
  
  /**
   * Quote the specified text if necessary.
   */
  static String quote(String text)
    {
      if (text.length() == 0 || text.indexOf(' ') != -1)
        {
          StringBuffer buffer = new StringBuffer();
          buffer.append('"');
          buffer.append(text);
          buffer.append('"');
          return buffer.toString();
        }
      return text;
    }
  
}
