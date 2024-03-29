// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <inttypes.h>
#include <sys/types.h>

namespace fsa {

/** utf8_t is the type of the multi-byte UTF-8 character components */
using utf8_t = uint8_t;
/** ucs4_t is the type of the 4-byte UCS4 characters */
using ucs4_t = uint32_t;


/**
 * @class Unicode
 * @brief Unicode character manipulation class.
 *
 * Utility class for unicode character handling.
 * Used to examine properties of unicode characters, and
 * provide fast conversion methods between often used encodings.
 */
class Unicode {
private:
  /** ISO 8859-1 digits. _isdigit[i] == 1 if i is a digit.
   */
  static const unsigned char _isdigit[256];
  /** ISO 8859-1 operators in integer index expressions.
   * _isintegerindexop[i] == 1 if i is a valid char in integer
   * range expressions, which is ';<>[]'.
   * This is maybe a bit specialized for the fastsearch application?
   */
  static const unsigned char _isintegerindexop[256];
  /** ISO 8859-1 wordchar identification.
   * _iswordchar[i] == 1 if i is a word character.
   * Wordchars are A-Z, a-z, 0-9, 0xC0-0xFF except 0xD7 and 0xF7.
   */
  static const unsigned char _iswordchar[256];
  /** ISO 8859-1 identifier start char.
   * _isidstartchar[i] == 1 if i is an id start character.
   * Is A-z, a-z.
   */
  static const unsigned char _isidstartchar[256];
  /** ISO 8859-1 identifier char.
   * _isidchar[i] == 1 if i is an id character.
   * Is A-z, a-z, 0-9, and '-', '_', ':', '.'.
   */
  static const unsigned char _isidchar[256];
  /** ISO 8859-1 space chars. _isspacechar[i] == 1 if i is a space char.
   * Space chars are ' ', '\\r', '\\t', '\\n'.
   */
  static const unsigned char _isspacechar[256];
  /**
   * ISO 8859-1 uppercase to lowercase mapping table.
   * _tolower[i] == j if j is the lowercase of i, else it is i (identity).
   * It is useful in the range A-Z, 0xC0-0xE0 except 0xD7.
   */
  static const unsigned char _tolower[256];
  /**
   * Table for easy lookup of UTF8 character length in bytes
   */
  static const unsigned char _utf8header[256];

  /** Two-level lowercase table. 256 pages, 256 elements each.
   * This table is defined in unicode-lowercase.cpp, which is
   * autogenerated by the extcase application. */
  static const unsigned short *_compLowerCase[256];

  /** Two-level character property table. 256 pages with 256 elements each.
   * This table is defined in unicode-charprops.cpp, which is
   * autogenerated by the extprop application. */
  static const unsigned char *_compCharProps[256];

public:

  /** The property bit identificators */
  enum {
    _spaceProp = 1,
    _wordcharProp = 2,
    _ideographicProp = 4,
    _decimalDigitCharProp = 8,
    _ignorableControlCharProp = 16
  };

  /** Indicates an invalid UTF-8 character sequence. */
  static const ucs4_t _BadUTF8Char =  0xfffffffeu;
  /** EndOfFile */
  static const ucs4_t _EOF =  0xffffffffu;

  /**
    * Return the 'raw' property bitmap.
    * @param testchar the UCS4 character to test.
    * @return unsigned char with the property bitmap.
    */
  static unsigned char getProperty(ucs4_t testchar) {
    if (testchar < 65536)
      return _compCharProps[testchar >> 8][testchar & 255];
    else
      return 0;
  }

  /**
    * Test for a specified property.
    * @param testchar the UCS4 character to test.
    * @param testprops the set of properties to test for.
    * @return true if testchar satisfies the specified set of properties.
    */
  static bool hasProperty(ucs4_t testchar, unsigned char testprops) {
    return (testchar < 65536 &&
            (_compCharProps[testchar >> 8][testchar & 255] & testprops) != 0);
  }

  /**
   * Test for word character. Characters with certain unicode properties
   * are recognized as word characters. In addition to this, all
   * characters with the custom _FASTWordProp is regarded as a word
   * character. The previous range in _privateUseProp is included
   * in the _FASTWordProp set of ranges.
   * @param testchar the UCS4 character to test.
   * @return true if testchar is a word character, i.e. if it has
   * one or more of the properties alphabetic, ideographic,
   * combining char, decimal digit char, private use, extender.
   */
  static bool isWordChar(ucs4_t testchar) {
    return (testchar < 65536 &&
        (_compCharProps[testchar >> 8][testchar & 255] &
         _wordcharProp) != 0);
  }

  /**
   * Test for ideographic character.
   * @param testchar the UCS4 character to test.
   * @return true if testchar is an ideographic character,
   *    i.e. if it has the ideographic property.
   */
  static bool isIdeographicChar(ucs4_t testchar) {
    return (testchar < 65536 &&
        (_compCharProps[testchar >> 8][testchar & 255] &
         _ideographicProp) != 0);
  }

  /**
   * Test for private use character. Implemented to
   * return true if character is in the range E000-F8FF,
   * since this is the only range of characters with
   * this property.
   * @param testchar the UCS4 character to test.
   * @return true if testchar is a private use character,
   *    i.e. if it has the private use property.
   */
  static bool isPrivateUseChar(ucs4_t testchar) {
    return (testchar >= 0xE000 && testchar <= 0xF8FF);
    //return (testchar < 65536 &&
            //(_compCharProps[testchar >> 8][testchar & 255] &
            //(_privateUseProp)) != 0);
  }

  /**
   * Test for ignorable character.
   * @param testchar the UCS4 character to test.
   * @return true if testchar is an ignorable character,
   *    i.e. if it has the ignorable control char property.
   */
  static bool isIgnorableChar(ucs4_t testchar) {
    return (testchar < 65536 &&
        (_compCharProps[testchar >> 8][testchar & 255] &
         _ignorableControlCharProp) != 0);
  }

  /**
   * Test for identificator start character.
   * InitTables should be called before using this test.
   * @param testchar the UCS4 character to test.
   * @return true if testchar is an identificator start character.
   */
  static bool isIDStartChar(ucs4_t testchar)
  {
    return (testchar < 256 && _isidstartchar[testchar] != 0);
  }

  /**
   * Test for identificator character.
   * InitTables should be called before using this test.
   * @param testchar the UCS4 character to test.
   * @return true if testchar is an identificator character.
   */
  static bool isIDChar(ucs4_t testchar)
  {
    return (testchar < 256 && _isidchar[testchar] != 0);
  }

  /**
   * Test for digit character.
   * @param testchar the UCS4 character to test.
   * @return true if testchar is a digit character,
   *    i.e. if it has the decimal digit char property.
   */
  static bool isDigit(ucs4_t testchar)
  {
    return (testchar < 65536 &&
        (_compCharProps[testchar >> 8][testchar & 255] &
         _decimalDigitCharProp) != 0);
  }

  /**
   * Test for integer range expression character.
   * InitTables should be called before using this test.
   * @param testchar the UCS4 character to test.
   * @return true if testchar is an integer range expression character.
   */
  static bool isIntegerIndexOp(ucs4_t testchar)
  {
    return (testchar < 256 && _isintegerindexop[testchar] != 0);
  }

  /**
   * Test for space character.
   * @param testchar the UCS4 character to test.
   * @return true if testchar is a space character,
   *    i.e. if it has the space char property.
   */
  static bool isSpaceChar(ucs4_t testchar)
  {
    return (testchar < 65536 &&
        (_compCharProps[testchar >> 8][testchar & 255] &
         _spaceProp) != 0);
  }

  /**
   * Test for uppercase character.
   * @param testchar the UCS4 character to test.
   * @return true if testchar is an uppercase character.
   */
  static bool isUpper(ucs4_t testchar)
  {
    if (testchar >= 65536)
      return false;
    ucs4_t ret = _compLowerCase[testchar >> 8][testchar & 255];
    return (ret != 0 && ret != testchar);
  }

  /**
   * Lowercase an UCS4 character.
   * @param testchar The character to lowercase.
   * @return The lowercase of the input, if defined. Else the input character.
   */
  static ucs4_t toLower(ucs4_t testchar)
  {
    ucs4_t ret;
    if (testchar < 65536) {
      ret = _compLowerCase[testchar >> 8][testchar & 255];
      if (ret == 0)
        return testchar;
      return ret;
    } else
      return testchar;
  }

  /**
   * Get the length of the UTF-8 representation of an UCS4 character.
   * @param i The UCS4 character.
   * @return The number of bytes required for the UTF-8 representation.
   */
  static size_t utf8clen(ucs4_t i) {
    if (i < 128)
      return 1;
    else if (i < 0x800)
      return 2;
    else if (i < 0x10000)
      return 3;
    else if (i < 0x200000)
      return 4;
    else if (i < 0x4000000)
      return 5;
    else
      return 6;
  }

  /**
   * Get the length of the UTF8 character in number of bytes
   * @param utf8char the first byte in a UTF8 character
   * @return the number of bytes in the UTF8 character
   */
  static unsigned char getUTF8ByteLength(unsigned char utf8char) {
    return _utf8header[utf8char];
  }

  /**
   * Put an UCS4 character into a buffer as an UTF-8 representation.
   * @param dst The destination buffer.
   * @param i The UCS4 character.
   * @return Pointer to the next position in dst after the putted byte(s).
   */
  static char *utf8cput(char *dst, ucs4_t i) {
    if (i < 128)
      *dst++ = i;
    else if (i < 0x800) {
      *dst++ = (i >> 6) | 0xc0;
      *dst++ = (i & 63) | 0x80;
    } else if (i < 0x10000) {
      *dst++ = (i >> 12) | 0xe0;
      *dst++ = ((i >> 6) & 63) | 0x80;
      *dst++ = (i & 63) | 0x80;
    } else if (i < 0x200000) {
      *dst++ = (i >> 18) | 0xf0;
      *dst++ = ((i >> 12) & 63) | 0x80;
      *dst++ = ((i >> 6) & 63) | 0x80;
      *dst++ = (i & 63) | 0x80;
    } else if (i < 0x4000000) {
      *dst++ = (i >> 24) | 0xf8;
      *dst++ = ((i >> 18) & 63) | 0x80;
      *dst++ = ((i >> 12) & 63) | 0x80;
      *dst++ = ((i >> 6) & 63) | 0x80;
      *dst++ = (i & 63) | 0x80;
    } else {
      *dst++ = (i >> 30) | 0xfc;
      *dst++ = ((i >> 24) & 63) | 0x80;
      *dst++ = ((i >> 18) & 63) | 0x80;
      *dst++ = ((i >> 12) & 63) | 0x80;
      *dst++ = ((i >> 6) & 63) | 0x80;
      *dst++ = (i & 63) | 0x80;
    }
    return dst;
  }


  /**
   * Convert UCS4 to UTF-8.
   * @param dst The destination buffer for the UTF-8 string.
   * @param src The source UCS4 string.
   * @return A pointer to the destination.
   */
  static char *utf8copy(char *dst, const ucs4_t *src);

  /**
   * Convert UCS4 to UTF-8, bounded by max lengths.
   * @param dst The destination buffer for the UTF-8 string.
   * @param src The source UCS4 string.
   * @param maxdst The maximum number of bytes to put into dst.
   * @param maxsrc The maximum number of characters to convert from src.
   * @return A pointer to the destination.
   */
 static char *utf8ncopy(char *dst, const ucs4_t *src, int maxdst, int maxsrc);

  /**
   * Compare an UTF-8 string to a UCS4 string, analogous to strcmp(3).
   * @param s1 The UTF-8 string.
   * @param s2 The UCS4 string.
   * @return An integer less than, equal to, or greater than zero,
   *        if s1 is, respectively, less than, matching, or greater than s2.
   */
  static int utf8cmp(const char *s1, const ucs4_t *s2);

  /**
   * Compare an UTF-8 string to a UCS4 string, ignoring case.
   * This is comparable to strcasecmp(3).
   * @param s1 The UTF-8 string.
   * @param s2 The UCS4 string.
   * @return An integer less than, equal to, or greater than zero,
   *        if s1 is, respectively, less than, matching, or greater than s2.
   */
  static int utf8casecmp(const char *s1, const ucs4_t *s2);

  /**
   * Find the length, in bytes, of the UTF-8 representation of an UCS4 string.
   * @param str The UCS4 string.
   * @return The length, in bytes, of the equivalent UTF-8 representation.
   */
  static size_t utf8len(const ucs4_t *str);

  /**
   * Find the length, in bytes, of the UTF-8 representation of the first
   * maxsrc characters of an UCS4 string.
   * @param str The UCS4 string.
   * @param maxsrc The maximum number of UCS4 characters to consider.
   * @return The length, in bytes, of the equivalent UTF-8 representation.
   */
  static size_t utf8nlen(const ucs4_t *str, int maxsrc);

  /**
   * Find the number of characters in an UCS4 string.
   * @param str The UCS4 string.
   * @return The number of characters.
   */
  static size_t ucs4strlen(const ucs4_t *str);

  /**
   * Find the number of UCS4 characters in an UTF-8 string. I.e.
   * how many UCS4 characters would be needed for the string.
   * @param str The UTF-8 string.
   * @return The number of characters needed.
   */
  static size_t ucs4len(const char *str);

  /**
   * Find the number of characters in an UTF-8 string, up to
   * a maximum of bytes.
   * @param str The UTF-8 string.
   * @param n The max number of bytes to consider.
   * @return The number of characters needed.
   */
  static size_t ucs4nlen(const char *str, size_t n);

  /**
   * Copy an UTF-8 string into an UCS4 string.
   * @param dst The UCS4 destination buffer.
   * @param src The UTF-8 source buffer.
   * @return A pointer to the destination string.
   */
  static ucs4_t *ucs4copy(ucs4_t *dst, const char *src);

  /**
   * Copy an UTF-8 string into an UCS4 string, up to a maximum
   * number of bytes from the UTF-8 string.
   * @param dst Destination UCS4 string buffer.
   * @param src Source UTF-8 string.
   * @param maxsrc Max number of bytes to copy.
   * @return Pointer to the destination buffer.
   */
  static ucs4_t *ucs4ncopy(ucs4_t *dst, const char *src, int maxsrc);

  /**
   * Copy an UTF-8 string to an UTF-8 string.
   * This only copies the valid UTF-8 characters.
   * @param src The source UTF-8 string.
   * @return Pointer to a new allocated buffer with the result.
   */
  static char *strdupUTF8(const char *src);

  /**
   * Copy an UTF-8 string to an UTF-8 string, converting
   * to lowercase as we go.
   * @param src The source UTF-8 string.
   * @return Pointer to a new allocated buffer with the result.
   */
  static char *strlowdupUTF8(const char *src);

  /**
   * Copy an ISO-8859-1 string to an UTF-8 string.
   * @param src The source ISO-8859-1 string.
   * @return Pointer to a new alloacted buffer with the UTF-8 result.
   */
  static char *strdupLAT1(const char *src);

  /**
   * Get the next UCS4 character from an UTF-8 string buffer.
   * Modify the src pointer to allow future calls.
   * @param src The address of a pointer to the current position
   *            in the UTF-8 string.
   * @param length The maximum allowed length of the byte sequence.
   *               -1 means no check.
   * @return The next UCS4 character, or _BadUTF8Char if the
   *         next character is invalid.
   */
  static ucs4_t getUTF8Char(unsigned const char *&src,
                            int length = -1);
  static ucs4_t getUTF8Char(char *&src,
                            int length = -1)
  {
    unsigned const char *temp = reinterpret_cast<unsigned char*>(src);
    ucs4_t res=getUTF8Char(temp,length);
    src=reinterpret_cast<char*>(const_cast<unsigned char*>(temp));
    return res;
  }


  /** Move forwards or backwards a number of characters within an UTF8 buffer
   * Modify pos to yield new position if possible
   * @param start A pointer to the start of the UTF8 buffer
   * @param length The length of the UTF8 buffer
   * @param pos A pointer to the current position within the UTF8 buffer,
   *            updated to reflect new position upon return
   * @param offset An offset (+/-) in number of UTF8 characters.
   *        Offset 0 means move to the start of the current character.
   * @return Number of bytes moved, or -1 if out of range
   */
  static int utf8move(unsigned const char* start, size_t length,
                      unsigned const char*& pos, off_t offset);
};

} // namespace fsa

