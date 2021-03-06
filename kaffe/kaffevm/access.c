/*
 * access.c
 * Access flags related functions.
 *
 * Copyright (c) 2002, 2003 University of Utah and the Flux Group.
 * All rights reserved.
 *
 * Copyright (c) 2005
 *      The kaffe.org's developers. See ChangeLog for details.
 *
 * This file is licensed under the terms of the GNU Public License.
 * See the file "license.terms" for information on usage and redistribution
 * of this file, and for a DISCLAIMER OF ALL WARRANTIES.
 *
 * Contributed by the Flux Research Group, Department of Computer Science,
 * University of Utah, http://www.cs.utah.edu/flux/
 */


#include "config.h"

/* include system headers */

#ifdef HAVE_SYS_TYPES_H
#include <sys/types.h>
#endif

#ifdef HAVE_SYS_TIME_H
#include <sys/time.h>
#endif

#ifdef HAVE_SYS_RESOURCE_H
#include <sys/resource.h>
#endif

#ifdef HAVE_STRING_H
#include <string.h>
#endif

#include "gtypes.h"
#include "exception.h"
#include "errors.h"
#include "access.h"
#include "support.h"
#include "classMethod.h"
#include "lookup.h"
#include "soft.h"


const char *checkAccessFlags(access_type_t type, accessFlags access_flags)
{
	const char *retval = NULL;

	if( (type == ACC_TYPE_CLASS) &&
	    (access_flags & ACC_INTERFACE) &&
	    !(access_flags & ACC_ABSTRACT) )
	{
		retval = "Abstract flag not set on interface";
	}
	else if( (type == ACC_TYPE_CLASS) &&
		 (access_flags & ACC_INTERFACE) &&
		 (access_flags & ACC_FINAL) )
	{
		retval = "Interfaces may only have the public and abstract "
			"flags set";
	}
	else if( (type == ACC_TYPE_CLASS) &&
		 ((access_flags & (ACC_FINAL | ACC_ABSTRACT)) ==
		  (ACC_FINAL | ACC_ABSTRACT)) )
	{
		retval = "Classes cannot have both final and abstract flags";
	}
	else if( (type == ACC_TYPE_CLASS) &&
		 (access_flags & (ACC_PROTECTED | ACC_PRIVATE)) )
	{
		retval = "Classes can only be public or package visible";
	}
	else if( bitCount(access_flags & (ACC_PUBLIC |
					  ACC_PRIVATE |
					  ACC_PROTECTED)) > 1 )
	{
		retval = "More than one protection flag set";
	}
	else if( (access_flags & (ACC_FINAL | ACC_VOLATILE)) ==
		 (ACC_FINAL | ACC_VOLATILE) )
	{
		retval = "Final and volatile cannot both be set";
	}
	else if( (type == ACC_TYPE_INTERFACE_FIELD) &&
		 ((access_flags & (ACC_PUBLIC | ACC_STATIC | ACC_FINAL)) !=
		  (ACC_PUBLIC | ACC_STATIC | ACC_FINAL)) )
	{
		retval = "Interface fields must have the public, static, and "
			"final flags set";
	}
	else if( (type == ACC_TYPE_INTERFACE_METHOD) &&
		 ((access_flags & (ACC_PUBLIC | ACC_ABSTRACT)) !=
		  (ACC_PUBLIC | ACC_ABSTRACT)) )
	{
		retval = "Interface methods must have the public and abstract "
			"flags set";
	}
	else if( ((type == ACC_TYPE_METHOD) ||
		  (type == ACC_TYPE_INTERFACE_METHOD)) &&
		 (access_flags & ACC_ABSTRACT) &&
		 (access_flags & (ACC_FINAL |
				  ACC_NATIVE |
				  ACC_PRIVATE |
				  ACC_STATIC |
				  ACC_SYNCHRONISED)) )
	{
		retval = "Abstract is incompatible with final, native, "
			"private, static, strict, and synchronized";
	}
	return( retval );
}

int checkAccess(struct Hjava_lang_Class *context,
		struct Hjava_lang_Class *target,
		accessFlags target_flags)
{
	int class_acc = 0, slot_acc = 0, same_package = 0;
	
	assert(context);
	assert(target);
	
	if( context == target )
	{
		/* Same class. */
		class_acc = 1;
		slot_acc = 1;

		return 1;
	}
	else if( target->accflags & ACC_PUBLIC )
	{
		/* Public class. */
		class_acc = 1;
	}
	/* Sun's VM spec does not refer to the case where the target class
	 * is protected.  But our experience tells the need for a special
	 * handling of this case.
	 */
	else if( instanceof(target, context) )
	{
		class_acc = 1;
	}
	
	if((context->packageLength == target->packageLength) &&
	    !strncmp(context->name->data,
		     target->name->data,
		     context->packageLength) )
	{
		same_package = 1;
		/* Package */
		class_acc = 1;
	}

	if( target_flags & ACC_PUBLIC )
	{
		/* Public slot. */
		slot_acc = 1;
	}
	else if( (target_flags & ACC_PROTECTED) &&
		 instanceof(target, context) )
	{
		/* Subclass. */
		slot_acc = 1;
	}
	else if( same_package && !(target_flags & ACC_PRIVATE) )
	{
		/* Package. */
		slot_acc = 1;
	}
	return( class_acc && slot_acc );
}

/*
 * Helper method for checkMethodAccess, finds the next super class that
 * contains the given method.  XXX is this right?
 *
 * cl - The current class, the search starts at this class' super class.
 * returns - The next super class containing the given method or NULL if no
 *   more classes were found to have the method.
 */
static
Hjava_lang_Class *findSuperMethod(Hjava_lang_Class *orig_cl, Method *meth)
{
	Hjava_lang_Class *cl, *retval = NULL;

	for( cl = orig_cl->superclass; cl && !retval; cl = cl->superclass )
	{
		int lpc;

		for( lpc = 0; lpc < CLASS_NMETHODS(cl) && !retval; lpc++ )
		{
			if( Kaffe_get_class_methods(cl)[lpc].idx == meth->idx )
			{
				retval = orig_cl->superclass;
			}
		}
	}
	return( retval );
}

int checkMethodAccess(struct Hjava_lang_Class *context,
		      struct Hjava_lang_Class *target,
		      Method *meth)
{
	int retval = 0;

	if( (target == meth->class) ||
	    checkMethodAccess(target, meth->class, meth) )
	{
		Hjava_lang_Class *cl;
		
		cl = target;
		while( cl && !retval )
		{
			if( checkAccess(context, cl, meth->accflags) )
			{
				/* Good to go. */
				retval = 1;
			}
			else if( meth->idx != -1 )
			{
				/* Look for the method in a super class. */
				cl = findSuperMethod(cl, meth);
			}
			else if( meth->class != cl )
			{
				/*
				 * The method is final/static and not in the
				 * current class, try the super.
				 */
				cl = cl->superclass;
			}
			else
			{
				cl = NULL;
			}
		}
	}

	return( retval );
}

static
Hjava_lang_Class *findSuperField(Hjava_lang_Class *orig_cl, Field *field)
{
	Hjava_lang_Class *retval = NULL;

	if( field->clazz != orig_cl )
	{
		retval = orig_cl->superclass;
	}
	return( retval );
}

int checkFieldAccess(struct Hjava_lang_Class *context,
		     struct Hjava_lang_Class *target,
		     Field *field)
{
	int retval = 0;

	if( (target == field->clazz) ||
	    checkFieldAccess(target, field->clazz, field) )
	{
		Hjava_lang_Class *cl;
		
		cl = target;
		while( cl && !retval )
		{
			if( checkAccess(context, cl, field->accflags) )
			{
				/* Good to go. */
				retval = 1;
			}
			else
			{
				/* Look for the method in a super class. */
				cl = findSuperField(cl, field);
			}
		}
	}
	return( retval );
}
