package com.rs.ocportable

/**
 * JavaScript injected after the page loads.
 *
 * We deliberately do not edit the served HTML: its CSP hashes the one inline script it carries,
 * and a modified page fails to boot under its own policy. `evaluateJavascript` runs outside that
 * policy and leaves the artifact untouched.
 */
object ComposerBridge {

    /** Installs `window.__oc_setComposerText(text)`; safe to call repeatedly. */
    val INSTALL = """
        (function () {
          if (window.__oc_setComposerText) return;
          window.__oc_setComposerText = function (text) {
            var el = document.querySelector('textarea:not([disabled])') ||
                     document.querySelector('[contenteditable="true"]');
            if (!el) return false;
            el.focus();
            if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
              var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
              setter.call(el, text);
            } else {
              var range = document.createRange();
              range.selectNodeContents(el);
              var selection = window.getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              if (!document.execCommand('insertText', false, text)) {
                el.textContent = text;
              }
            }
            var event;
            try {
              event = new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text });
            } catch (e) {
              event = new Event('input', { bubbles: true });
            }
            el.dispatchEvent(event);
            el.dispatchEvent(new Event('change', { bubbles: true }));
            return true;
          };
        })();
    """.trimIndent()

    /** The six WebView tells, minus anything that would fight the app's own responsive layout. */
    private val MOBILE_CSS = """
        html{-webkit-tap-highlight-color:transparent;overscroll-behavior:none;-webkit-text-size-adjust:100%}
        button,a,[role="button"]{touch-action:manipulation;user-select:none}
        input,textarea{font-size:16px}
    """.trimIndent()

    val CSS = """
        (function () {
          var style = document.getElementById('oc-portable-css');
          if (!style) {
            style = document.createElement('style');
            style.id = 'oc-portable-css';
            (document.head || document.documentElement).appendChild(style);
          }
          style.textContent = ${jsString(MOBILE_CSS)};
        })();
    """.trimIndent()

    fun setTextScript(text: String): String =
        "window.__oc_setComposerText && window.__oc_setComposerText(${jsString(text)});"

    /** A JavaScript string literal. JSON quoting would be fine; this keeps the function testable. */
    fun jsString(value: String): String = buildString {
        append('"')
        for (character in value) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (character < ' ') {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
