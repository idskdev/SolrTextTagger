package org.opensextant.solrtexttagger;

import com.carrotsearch.hppc.IntArrayList;

import java.util.Arrays;

public abstract class OffsetCorrector {

  //TODO support a streaming style of consuming input text so that we need not take a
  // String. Trickier because we need to keep more information as we parse to know when tags
  // are adjacent with/without whitespace

  //Data structure requirements:
  // Given a character offset:
  //   * determine what tagId is it's parent.
  //   * determine if it is adjacent to the parent open tag, ignoring whitespace
  //   * determine if it is adjacent to the parent close tag, ignoring whitespace
  // Given a tagId:
  //   * What is it's parent tagId
  //   * What's the char offset of the start and end of the open tag
  //   * What's the char offset of the start and end of the close tag

  /** Document text. */
  protected final String docText;

  /** Array of tag info comprised of 5 int fields:
   *    [int parentTag, int openStartOff, int openEndOff, int closeStartOff, int closeEndOff].
   * It's size indicates how many tags there are. Tag's are ID'ed sequentially from 0. */
  protected final IntArrayList tagInfo;

  /** offsets of parent tag id change (ascending order) */
  protected final IntArrayList parentChangeOffsets;
  /** tag id; parallel array to parentChangeOffsets */
  protected final IntArrayList parentChangeIds;

  protected final int[] offsetPair = new int[] { -1, -1};//non-thread-safe state

  /**
   * Initialize based on the document text.
   * @param docText non-null structured content.
   */
  protected OffsetCorrector(String docText) {
    this.docText = docText;
    final int guessNumElements = Math.max(docText.length() / 20, 4);

    tagInfo = new IntArrayList(guessNumElements * 5);
    parentChangeOffsets = new IntArrayList(guessNumElements * 2);
    parentChangeIds = new IntArrayList(guessNumElements * 2);
  }

  /** Corrects the start and end offset pair. It will return null if it can't
   * due to a failure to keep the offsets balance-able.
   * The start (left) offset is pulled left as needed over whitespace and opening tags. The end
   * (right) offset is pulled right as needed over whitespace and closing tags. It's returned as
   * a 2-element array.
   * <p />Note that the returned array is internally reused; just use it to examine the response.
   */
  public int[] correctPair(int leftOffset, int rightOffset) {
    rightOffset = correctEndOffsetForCloseElement(rightOffset);

    int startTag = lookupTag(leftOffset);
    //offsetPair[0] = Math.max(offsetPair[0], getOpenStartOff(startTag));
    int endTag = lookupTag(rightOffset);
    //offsetPair[1] = Math.min(offsetPair[1], getCloseStartOff(endTag));

    // Find the ancestor tag enclosing offsetPair.  And bump out left offset along the way.
    int iTag = startTag;
    for (; !tagEnclosesOffset(iTag, rightOffset); iTag = getParentTag(iTag)) {
      //Ensure there is nothing except whitespace thru OpenEndOff
      int tagOpenEndOff = getOpenEndOff(iTag);
      if (hasNonWhitespace(tagOpenEndOff, leftOffset))
        return null;
      leftOffset = getOpenStartOff(iTag);
    }
    final int ancestorTag = iTag;
    // Bump out rightOffset until we get to ancestorTag.
    for (iTag = endTag; iTag != ancestorTag; iTag = getParentTag(iTag)) {
      //Ensure there is nothing except whitespace thru CloseStartOff
      int tagCloseStartOff = getCloseStartOff(iTag);
      if (hasNonWhitespace(rightOffset, tagCloseStartOff))
        return null;
      rightOffset = getCloseEndOff(iTag);
    }

    offsetPair[0] = leftOffset;
    offsetPair[1] = rightOffset;
    return offsetPair;
  }

  /** Correct endOffset for closing element at the right side.  E.g. offsetPair might point to:
   * <pre>
   *   foo&lt;/tag&gt;
   * </pre>
   * and this method pulls the end offset left to the '&lt;'. This is necessary for use with
   * {@link org.apache.lucene.analysis.charfilter.HTMLStripCharFilter}.
   *
   * See https://issues.apache.org/jira/browse/LUCENE-5734 */
  protected int correctEndOffsetForCloseElement(int endOffset) {
    if (docText.charAt(endOffset-1) == '>') {
      final int newEndOffset = docText.lastIndexOf('<', endOffset - 2);
      if (newEndOffset > offsetPair[0])//just to be sure
        return newEndOffset;
    }
    return endOffset;
  }

  protected boolean hasNonWhitespace(int start, int end) {
    for (int i = start; i < end; i++) {
      if (!Character.isWhitespace(docText.charAt(i)))
        return true;
    }
    return false;
  }

  protected boolean tagEnclosesOffset(int tag, int off) {
    return off >= getOpenStartOff(tag) && off < getCloseEndOff(tag);
  }

  protected int getParentTag(int tag) { return tagInfo.get(tag * 5 + 0); }
  protected int getOpenStartOff(int tag) { return tagInfo.get(tag * 5 + 1); }
  protected int getOpenEndOff(int tag) { return tagInfo.get(tag * 5 + 2); }
  protected int getCloseStartOff(int tag) { return tagInfo.get(tag * 5 + 3); }
  protected int getCloseEndOff(int tag) { return tagInfo.get(tag * 5 + 4); }

  protected int lookupTag(int off) {
    int idx = Arrays.binarySearch(parentChangeOffsets.buffer, 0, parentChangeOffsets.size(), off);
    if (idx < 0)
      idx = (-idx - 1) - 1;//round down
    return parentChangeIds.get(idx);
  }
}