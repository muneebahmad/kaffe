/*
 * utf8const.c
 *
 * Handle UTF-8 constant strings. These are intern'ed into a hash table.
 *
 * Copyright (c) 1998
 *	Transvirtual Technologies, Inc.  All rights reserved.
 */

#include "config.h"
#include "config-std.h"
#include "config-mem.h"
#include "config-io.h"
#include "classMethod.h"
#include "jtypes.h"
#include "constants.h"
#include "object.h"
#include "itypes.h"
#include "locks.h"
#include "jsyscall.h"
#include "hashtab.h"
#include "stringSupport.h"
#include "stats.h"

/* For kaffeh, don't use the hash table or locks. Instead, just make these
   function calls into macros in such a way as to avoid compiler warnings.
   Yuk! */
#ifdef KAFFEH
#undef  initStaticLock
#define initStaticLock(x)
#undef  staticLockIsInitialized		0
#define staticLockIsInitialized(x)	1
#undef  lockStaticMutex
#undef  unlockStaticMutex
#define unlockStaticMutex(x)
#define lockStaticMutex(x)
#define hashInit(a,b,c,d)	((hashtab_t)((u_int)utf8ConstCompare \
					+ (u_int)utf8ConstHashValueInternal))
#define hashAdd(t, x)		(x)
#define hashFind(t, x)		NULL
#define hashRemove(t, x)	(void)NULL
#endif

/* Internal variables */
#ifndef KAFFEH				/* Yuk! */
static hashtab_t	hashTable;
static iLock*		utf8Lock;	/* mutex on all intern operations */

static int *utfLockRoot;

static inline void lockUTF(int *where)
{
	_lockMutex(&utf8Lock, where);
	utfLockRoot = where;
}

#define lockUTF()   lockUTF(&iLockRoot)
#define unlockUTF() _unlockMutex(&utf8Lock, &iLockRoot)

static inline void *UTFmalloc(size_t size)
{
	void *ret;
	int *myRoot = utfLockRoot;
	
	_unlockMutex(&utf8Lock, myRoot);
	ret = gc_malloc(size, GC_ALLOC_UTF8CONST);
	_lockMutex(&utf8Lock, myRoot);
	utfLockRoot = myRoot;
	return ret;
}

static inline void UTFfree(const void *mem)
{
	int *myRoot = utfLockRoot;

	_unlockMutex(&utf8Lock, myRoot);
	jfree((void *)mem);
	_lockMutex(&utf8Lock, myRoot);
	utfLockRoot = myRoot;
}
#else
static hashtab_t	hashTable = (hashtab_t)1;

#define lockUTF()
#define unlockUTF() 
#define UTFmalloc(size) malloc(size)
#define UTFfree(size)   free(size)
#endif

/* Internal functions */
static int		utf8ConstHashValueInternal(const void *v);
static int		utf8ConstCompare(const void *v1, const void *v2);

/*
 * Convert a non-terminated UTF-8 string into an interned Utf8Const.
 */
Utf8Const *
utf8ConstNew(const char *s, int len)
{
	Utf8Const *utf8, *temp;
	int32 hash;
	Utf8Const *fake;
	char buf[200];
#if !defined(KAFFEH)
	int iLockRoot;
#endif

	/* Automatic length finder */
	if (len < 0) {
		len = strlen(s);
	}

#ifdef DEBUG
	assert(utf8ConstIsValidUtf8(s, len));
#endif
	hitCounter(&utf8new, "utf8-new");

	/* Precompute hash value using String.hashCode() algorithm */
	{
		const char *ptr = s;
		const char *const end = s + len;
		int ch;

		for (hash = 0;
		    (ch = UTF8_GET(ptr, end)) != -1;
		    hash = (31 * hash) + ch);
	}

	/* Lock intern table */
	lockUTF();

	/* See if string is already in the table using a "fake" Utf8Const */
	assert (hashTable != NULL);
	if (sizeof(Utf8Const) + len + 1 > sizeof(buf)) {
		fake = UTFmalloc(sizeof(Utf8Const) + len + 1);
		if (!fake) {
			unlockUTF();
			return 0;
		}
	} else {
		fake = (Utf8Const*)buf;
	}
	memcpy((char *)fake->data, s, len);
	((char *)fake->data)[len] = '\0';
	fake->hash = hash;
	utf8 = hashFind(hashTable, fake);
	if (utf8 != NULL) {
		assert(utf8->nrefs >= 1);
		utf8->nrefs++;
		unlockStaticMutex(&utf8Lock);
		if (fake != (Utf8Const*)buf) {
			jfree(fake);
		}
		return(utf8);
	}

	hitCounter(&utf8newalloc, "utf8-new-alloc");
	/* Not in table; create new Utf8Const struct */
	if ((char *) fake == buf) {
		utf8 = UTFmalloc(sizeof(Utf8Const) + len + 1);
		if (!utf8) {
			unlockStaticMutex(&utf8Lock);
			return 0;
		}
		memcpy((char *) utf8->data, s, len);
		((char*)utf8->data)[len] = '\0';
		utf8->hash = hash;
	} else {
		utf8 = fake;
	}
	
	utf8->nrefs = 1;

	/* Add to hash table */
	temp = hashAdd(hashTable, utf8);
	if (!temp) {
		unlockStaticMutex(&utf8Lock);
		jfree(utf8);
		return 0;
	}
	assert(temp == utf8);
	unlockStaticMutex(&utf8Lock);
	return(utf8);
}

/*
 * Add a reference to a Utf8Const.
 */
void
utf8ConstAddRef(Utf8Const *utf8)
{
#if !defined(KAFFEH)
	int iLockRoot;
#endif

	lockStaticMutex(&utf8Lock);
	assert(utf8->nrefs >= 1);
	utf8->nrefs++;
	unlockStaticMutex(&utf8Lock);
}

/*
 * Release a Utf8Const.
 */
void
utf8ConstRelease(Utf8Const *utf8)
{
#if !defined(KAFFEH)
	int iLockRoot;
#endif

	/* NB: we ignore zero utf8s here in order to not having to do it at
	 * the call sites, such as when destroying half-processed class 
	 * objects because of error conditions.
	 */
	if (utf8 == 0) {
		return;
	}
	lockStaticMutex(&utf8Lock);
	assert(utf8->nrefs >= 1);
	if (--utf8->nrefs == 0) {
		hitCounter(&utf8release, "utf8-release");
		hashRemove(hashTable, utf8);
	}
	unlockStaticMutex(&utf8Lock);
	if (utf8->nrefs == 0)
		jfree(utf8);
}

/*
 * Return hash value for the hash table.
 */
static int	
utf8ConstHashValueInternal(const void *v)
{
	const Utf8Const *const utf8 = v;

	return(utf8->hash);
}

/*
 * Compare Utf8Consts for the hash table.
 */
static int
utf8ConstCompare(const void *v1, const void *v2)
{
	const Utf8Const *const utf8_1 = v1;
	const Utf8Const *const utf8_2 = v2;

	return(strcmp(utf8_1->data, utf8_2->data));
}

/*
 * Check if a string is a valid UTF-8 string.
 */
int
utf8ConstIsValidUtf8(const char *ptr, unsigned int len)
{
	const char *const end = ptr + len;

	while (UTF8_GET(ptr, end) != -1);
	return(ptr == end);
}

/*
 * Compute Unicode length of a UTF-8 string.
 */
int
utf8ConstUniLength(const Utf8Const *utf8)
{
	const char *ptr = utf8->data;
	const char *const end = ptr + strlen(utf8->data);
	int uniLen;

	for (uniLen = 0; UTF8_GET(ptr, end) != -1; uniLen++);
	assert(ptr == end);
	return(uniLen);
}

/*
 * Decode a UTF-8 string into Unicode. The buffer must be
 * big enough to hold utf8ConstUniLength(utf8) jchar's.
 */
void
utf8ConstDecode(const Utf8Const *utf8, jchar *buf)
{
	const char *ptr = utf8->data;
	const char *const end = ptr + strlen(utf8->data);
	int ch;

	while ((ch = UTF8_GET(ptr, end)) != -1) {
		*buf++ = ch;
	}
	assert(ptr == end);
}

/*
 * Encode a jchar[] Array into a zero-terminated C string
 * that contains the array's utf8 encoding.
 *
 * NB.: Caller must free via KFREE.
 */
char *
utf8ConstEncode(const jchar *chars, int clength)
{
	int i, size = 0, pos = 0;
	char * buf;

	/* Size output array */
	for (i = 0; i < clength; i++) {
		jchar ch = chars[i];
		if (ch >= 0x0001 && ch <= 0x007f) {
			size++;
		} else if (ch <= 0x07ff) {
			size += 2;
		} else {
			size += 3;
		}
	}

	// Now fill it in
	buf = KMALLOC(size + 1);
	if (buf == 0) {
		return (0);
	}

	for (i = 0; i < clength; i++) {
		jchar ch = chars[i];
		if (ch >= 0x0001 && ch <= 0x007f) {
			buf[pos++] = (char) ch;
		} else if (ch <= 0x07ff) {
			buf[pos++] = (char) (0xc0 | (0x3f & (ch >> 6)));
			buf[pos++] = (char) (0x80 | (0x3f &  ch));
		} else {
			buf[pos++] = (char) (0xe0 | (0x0f & (ch >> 12)));
			buf[pos++] = (char) (0x80 | (0x3f & (ch >>  6)));
			buf[pos++] = (char) (0x80 | (0x3f &  ch));
		}
	}
	return (buf);
}

/*
 * Return true iff the Utf8Const string is equal to the Java String.
 */
int
utf8ConstEqualJavaString(const Utf8Const *utf8, const Hjava_lang_String *string)
{
	const char *uptr = utf8->data;
	const char *const uend = uptr + strlen(utf8->data);
	const jchar *sptr = STRING_DATA(string);
	int ch, slen = STRING_SIZE(string);

#if 0
	/* Question: would this optimization be worthwhile? */
	if (unhand(string)->hash != 0 && unhand(string)->hash != utf8->hash) {
		return(0);
	}
#endif
	for (;;) {
		if ((ch = UTF8_GET(uptr, uend)) == -1) {
			return(slen == 0);
		}
		if (--slen < 0) {
			return(0);
		}
		if (ch != *sptr++) {
			return(0);
		}
	}
}

/*
 * Initialize utf8const support system
 */
void
utf8ConstInit(void)
{
	hashTable = hashInit(utf8ConstHashValueInternal,
		utf8ConstCompare, UTFmalloc, UTFfree);
	assert(hashTable);
}
