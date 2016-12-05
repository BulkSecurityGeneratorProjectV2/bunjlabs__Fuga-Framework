package com.bunjlabs.fuga.templates;

import com.bunjlabs.fuga.resources.ResourceRepresenter;
import com.bunjlabs.fuga.templates.TemplateNotFoundException;
import com.bunjlabs.fuga.templates.TemplateReader.Token;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import jdk.nashorn.api.scripting.NashornScriptEngineFactory;

/**
 *
 * @author Artem Shurygin <artem.shurygin@bunjlabs.com>
 */
public class TemplateCompiler {

    private static final Pattern CODE_PATTERN = Pattern.compile(
            "(\\@([\\s\\S]*?)\\;)"
            + "|(\\@\\{([^\\{\\}\\n\\r]*?)\\})"
            + "|(\\<%([\\s\\S]*?)%\\>)"
            + "|(\\<#([^\\n\\r]*?)#\\>)");

    private final ResourceRepresenter resourceRepresenter;
    private final ScriptEngine engine = new NashornScriptEngineFactory().getScriptEngine();

    public TemplateCompiler(ResourceRepresenter resourceRepresenter) {
        this.resourceRepresenter = resourceRepresenter;
    }

    public Template compile(String name) throws TemplateNotFoundException, TemplateCompileException {
        InputStream is;
        try {
            is = resourceRepresenter.load(name.startsWith("/") ? name.substring(1) : name);
        } catch (FileNotFoundException ex) {
            throw new TemplateNotFoundException("Unable to load: " + name, ex);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));

        return compileTemplate(new TemplateReader(reader));
    }

    private Template compileTemplate(TemplateReader input) throws TemplateNotFoundException, TemplateCompileException {
        Template template = new Template();

        Token token;
        try {
            while ((token = input.next()).type != Token.EOS) {
                if (token.type == Token.EXTENDS) {
                    String extendName = token.args[0];
                    if (extendName.length() > 0) {
                        template.extend(compile(extendName));
                    }
                } else if (token.type == Token.USE) {
                    String useName = token.args[0];
                    if (useName.length() > 0) {
                        template.use(compile(useName));
                    }
                } else if (token.type == Token.BLOCK) {
                    String blockName = token.args[0];
                    String blockSource = token.args[1];
                    if (blockName.length() > 0 && blockSource.length() > 0) {
                        template.addBlock(blockName, compileBlock(blockSource));
                    }
                } else if (token.type == Token.CODE) {
                    String blockSource = token.args[0];
                    if (blockSource.length() > 0) {
                        template.addCodeBlock(compileCodeBlock(blockSource));
                    }
                } else {
                    throw new TemplateCompileException("Unexpected keyword: " + token.type);
                }
            }
        } catch (IOException ex) {
            throw new TemplateCompileException("Unable to load template", ex);
        }

        return template;
    }

    private ScriptBlock compileBlock(String input) throws TemplateCompileException {
        StringBuilder jsCode = new StringBuilder();

        while (input.length() > 0) {
            Matcher m = CODE_PATTERN.matcher(input);
            if (m.find()) {
                String text = input.substring(0, m.start());
                if (text.length() > 0) {
                    jsCode.append(String.format("print(\"%s\");", escapeSpaces(text)));
                }
                if (m.group(2) != null || m.group(4) != null) {
                    String code = m.group(2) != null ? m.group(2) : m.group(4);
                    jsCode.append(String.format("print(%s || '');", code.trim()));

                    input = input.substring(m.end());
                } else if (m.group(6) != null) {
                    String codeBlock = m.group(6).trim();

                    jsCode.append(codeBlock);
                    input = input.substring(m.end());

                } else if (m.group(8) != null) {
                    jsCode.append(String.format("print(%s || '');", m.group(8).trim()));
                    input = input.substring(m.end());
                }

            } else if (input.length() > 0) {
                jsCode.append(String.format("print(\"%s\");", escapeSpaces(input)));
                input = "";
            }
        }
        CompiledScript compiled;

        try {
            compiled = ((Compilable) engine).compile(jsCode.toString());
        } catch (ScriptException ex) {
            throw new TemplateCompileException(ex);
        }

        return new ScriptBlock(compiled);
    }

    private ScriptBlock compileCodeBlock(String input) throws TemplateCompileException {
        CompiledScript compiled;

        try {
            compiled = ((Compilable) engine).compile(input);
        } catch (ScriptException ex) {
            throw new TemplateCompileException(ex);
        }

        return new ScriptBlock(compiled);
    }

    private static String escapeSpaces(String input) {
        return input.replaceAll("\\\\", "\\\\\\\\")
                .replaceAll("\\t", "\\\\t")
                .replaceAll("\\n", "\\\\n")
                .replaceAll("\\r", "\\\\r")
                .replaceAll("\\f", "\\\\f")
                .replaceAll("\\'", "\\\\'")
                .replaceAll("\\\"", "\\\\\"");
    }
}
