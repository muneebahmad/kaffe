/*
 * SubstringFunction.java
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

package gnu.xml.xpath;

import java.util.List;
import org.w3c.dom.Node;

/**
 * The substring function returns the substring of the first argument
 * starting at the position specified in the second argument with length
 * specified in the third argument. For example, substring("12345",2,3)
 * returns "234". If the third argument is not specified, it returns the
 * substring starting at the position specified in the second argument and
 * continuing to the end of the string. For example, substring("12345",2)
 * returns "2345".
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class SubstringFunction
  extends Expr
{

	final Expr arg1;
	final Expr arg2;
	final Expr arg3;

	SubstringFunction(List args)
	{
		arg1 = (Expr) args.get(0);
		arg2 = (Expr) args.get(1);
		arg3 = (args.size() > 2) ? (Expr) args.get(2) : null;
	}

	public Object evaluate(Node context, int pos, int len)
	{
		Object val1 = arg1.evaluate(context, pos, len);
		Object val2 = arg2.evaluate(context, pos, len);
		String s = _string(context, val1);
		int p = (val2 instanceof Double) ?
			((Double) val2).intValue() :
				(int) Math.round(_number(context, val2));
		int l = s.length() + 1;
		if (arg3 != null)
		  {
				Object val3 = arg3.evaluate(context, pos, len);
				l = (val3 instanceof Double) ?
					((Double) val3).intValue() :
						(int) Math.round(_number(context, val3));
			}
		p--;
		l--;
		if (p < 0)
		  {
				p = 0;
			}
		if (l > s.length())
		  {
				l = s.length();
			}
		return s.substring(p, l);
	}

	public String toString()
	{
		return (arg3 == null) ? "substring(" + arg1 + "," + arg2 + ")" :
			"substring(" + arg1 + "," + arg2 + "," + arg3 + ")";
	}
	
}
