package com.bardosrs.llm;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Some models print their own tool-call syntax as plain text when tools are
 * switched off or something goes wrong (DeepSeek's "DSML" blocks, "tool_call"
 * tags, special tokens like {@code <|python_tag|>}). That text must never reach
 * the chat panel, so the stream reader holds back anything that could be the
 * start of such a marker and cuts the reply where one begins.
 */
final class LeakedMarkup
{
	/** "<｜DSML｜...", "<|special_token|>", "<tool_call", "<function_calls", "<DSML". */
	private static final Pattern MARKER = Pattern.compile(
		"<[\\uFF5C|][A-Za-z]|<(?:DSML|tool_calls?|function_calls?)\\b", Pattern.CASE_INSENSITIVE);

	private static final String[] WORD_MARKERS = {"<dsml", "<tool_call", "<tool_calls", "<function_call", "<function_calls"};
	private static final int MAX_PENDING = 16;

	private LeakedMarkup()
	{
	}

	/** Index where leaked markup starts at or after {@code from}, or -1. */
	static int find(CharSequence text, int from)
	{
		Matcher matcher = MARKER.matcher(text);
		return matcher.find(Math.max(0, from)) ? matcher.start() : -1;
	}

	/** How many characters at the end of {@code text} could still turn into a marker and must wait for more input. */
	static int pendingPrefixLength(CharSequence text)
	{
		int length = text.length();
		int earliest = Math.max(0, length - MAX_PENDING);
		for (int i = length - 1; i >= earliest; i--)
		{
			if (text.charAt(i) == '<')
			{
				return couldBecomeMarker(text.subSequence(i, length).toString()) ? length - i : 0;
			}
		}
		return 0;
	}

	private static boolean couldBecomeMarker(String tail)
	{
		if (tail.length() == 1)
		{
			return true;
		}
		char second = tail.charAt(1);
		if (tail.length() == 2 && (second == '｜' || second == '|'))
		{
			return true;
		}
		String lower = tail.toLowerCase(Locale.ROOT);
		for (String word : WORD_MARKERS)
		{
			if (word.startsWith(lower))
			{
				return true;
			}
		}
		return false;
	}
}
