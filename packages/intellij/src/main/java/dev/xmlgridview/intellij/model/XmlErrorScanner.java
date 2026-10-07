package dev.xmlgridview.intellij.model;

import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds well-formedness errors with the JDK's SAX parser. PSI recovers from
 * errors silently and reports positions differently, so error locations come
 * from here. External entities and DTDs are never loaded.
 */
public final class XmlErrorScanner {
  private XmlErrorScanner() {
  }

  public static List<ParseError> scan(String text) {
    List<ParseError> errors = new ArrayList<>();
    try {
      SAXParserFactory f = SAXParserFactory.newInstance();
      f.setNamespaceAware(true);
      f.setValidating(false);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      DefaultHandler handler = new DefaultHandler() {
        @Override
        public void error(SAXParseException e) {
          add(errors, text, e);
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXParseException {
          throw e;
        }
      };
      f.newSAXParser().parse(new InputSource(new StringReader(text)), handler);
    } catch (SAXParseException e) {
      add(errors, text, e);
    } catch (Exception e) {
      errors.add(new ParseError(String.valueOf(e.getMessage()), 1, 1, 0));
    }
    return errors;
  }

  private static void add(List<ParseError> errors, String text, SAXParseException e) {
    int line = Math.max(1, e.getLineNumber());
    int column = Math.max(1, e.getColumnNumber());
    errors.add(new ParseError(String.valueOf(e.getMessage()), line, column, TextUtil.offsetOf(text, line, column)));
  }
}
