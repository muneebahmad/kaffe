/*
 * AttributeNode.java
 * Copyright (C) 2004 The Free Software Foundation
 * 
 * This file is part of GNU JAXP, a library.
 *
 * GNU JAXP is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * GNU JAXP is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 * Linking this library statically or dynamically with other modules is
 * making a combined work based on this library.  Thus, the terms and
 * conditions of the GNU General Public License cover the whole
 * combination.
 *
 * As a special exception, the copyright holders of this library give you
 * permission to link this library with independent modules to produce an
 * executable, regardless of the license terms of these independent
 * modules, and to copy and distribute the resulting executable under
 * terms of your choice, provided that you also meet, for each linked
 * independent module, the terms and conditions of the license of that
 * module.  An independent module is a module which is not derived from
 * or based on this library.  If you modify this library, you may extend
 * this exception to your version of the library, but you are not
 * obliged to do so.  If you do not wish to do so, delete this
 * exception statement from your version. 
 */

package gnu.xml.transform;

import javax.xml.transform.TransformerException;
import org.w3c.dom.Document;
import org.w3c.dom.Attr;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * A template node representing an XSL <code>attribute</code> instruction.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class AttributeNode
  extends TemplateNode
{

  final String name;
  final String namespace;
  
  AttributeNode(TemplateNode children, TemplateNode next, String name,
                String namespace)
  {
    super(children, next);
    this.name = name;
    this.namespace = namespace;
  }

  void apply(Stylesheet stylesheet, String mode,
             Node context, int pos, int len,
             Node parent, Node nextSibling)
    throws TransformerException
  {
    Document doc = (parent instanceof Document) ? (Document) parent :
      parent.getOwnerDocument();
    Attr attr = (namespace != null) ?
      doc.createAttributeNS(namespace, name) :
      doc.createAttribute(name);
    NamedNodeMap attrs = parent.getAttributes();
    if (attrs != null)
      {
        if (namespace != null)
          {
            attrs.setNamedItemNS(attr);
          }
        else
          {
            attrs.setNamedItem(attr);
          }
      }
    if (children != null)
      {
        children.apply(stylesheet, mode,
                       context, pos, len,
                       attr, null);
      }
    if (next != null)
      {
        next.apply(stylesheet, mode,
                   context, pos, len,
                   parent, nextSibling);
      }
  }
  
  public String toString()
  {
    StringBuffer buf = new StringBuffer(getClass().getName());
    buf.append('[');
    buf.append("name=");
    buf.append(name);
    buf.append(']');
    return buf.toString();
  }
  
}
