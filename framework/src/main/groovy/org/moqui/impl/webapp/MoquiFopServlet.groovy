/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.webapp

import groovy.transform.CompileStatic
import org.moqui.context.ArtifactTarpitException
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.util.StringUtilities

import javax.servlet.ServletConfig
import javax.servlet.ServletException
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import org.moqui.screen.ScreenRender
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.impl.context.ExecutionContextFactoryImpl

import javax.xml.transform.stream.StreamSource

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class MoquiFopServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiFopServlet.class)

    MoquiFopServlet() {
        super()
    }

    @Override
    void init(ServletConfig config) throws ServletException {
        super.init(config)
        String webappName = config.getInitParameter("moqui-name") ?: config.getServletContext().getInitParameter("moqui-name")
        logger.info("${config.getServletName()} initialized for webapp ${webappName}")
    }

    @Override
    void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        ExecutionContextFactoryImpl ecfi =
                (ExecutionContextFactoryImpl) getServletContext().getAttribute("executionContextFactory")
        String moquiWebappName = getServletContext().getInitParameter("moqui-name")

        if (ecfi == null || moquiWebappName == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "System is initializing, try again soon.")
            return
        }

        // handle CORS actual and preflight request headers
        if (MoquiServlet.handleCors(request, response, moquiWebappName, ecfi)) return

        long startTime = System.currentTimeMillis()

        if (logger.traceEnabled) logger.trace("Start request to [${request.getPathInfo()}] at time [${startTime}] in session [${request.session.id}] thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")

        ExecutionContextImpl activeEc = ecfi.activeContext.get()
        if (activeEc != null) {
            logger.warn("In MoquiServlet.service there is already an ExecutionContext for user ${activeEc.user.username} (from ${activeEc.forThreadId}:${activeEc.forThreadName}) in this thread (${Thread.currentThread().id}:${Thread.currentThread().name}), destroying")
            activeEc.destroy()
        }
        ExecutionContextImpl ec = ecfi.getEci()

        String xslFoText = null
        try {
            ec.initWebFacade(moquiWebappName, request, response)
            ec.web.requestAttributes.put("moquiRequestStartTime", startTime)

            ArrayList<String> pathInfoList = ec.web.getPathInfoList()
            ScreenRender sr = ec.screen.makeRender().webappName(moquiWebappName).renderMode("xsl-fo")
                    .rootScreenFromHost(request.getServerName()).screenPath(pathInfoList)
            xslFoText = sr.render()

            // the document's own data step refused (a user-facing message, not a crash): show it on a plain page
            if (ec.message.hasError()) {
                sendDocumentRefused(response, ec.message.getErrors())
                return
            }

            // logger.warn("======== XSL-FO content:\n${xslFoText}")
            if (logger.traceEnabled) logger.trace("XSL-FO content:\n${xslFoText}")

            String contentType = (String) ec.web.requestParameters."contentType" ?: "application/pdf"
            response.setContentType(contentType)

            String filename = (ec.web.parameters.get("filename") as String) ?: (ec.web.parameters.get("saveFilename") as String)
            if (filename) {
                String utfFilename = StringUtilities.encodeAsciiFilename(filename)
                response.setHeader("Content-Disposition", "attachment; filename=\"${filename}\"; filename*=utf-8''${utfFilename}")
            } else {
                response.setHeader("Content-Disposition", "inline")
            }

            // special case disable authz for resource access
            boolean enableAuthz = !ecfi.getExecutionContext().getArtifactExecution().disableAuthz()
            try {
                /* FUTURE: pre-render to get page count, then pass in final rendered streamed to client
                Integer pageCount = ec.resource.xslFoTransform(new StreamSource(new StringReader(xslFoText)), null,
                        org.apache.commons.io.output.NullOutputStream.NULL_OUTPUT_STREAM, contentType)
                logger.info("Rendered ${pathInfo} as ${contentType} has ${pageCount} pages")
                */
                ec.resource.xslFoTransform(new StreamSource(new StringReader(xslFoText)), null,
                        response.getOutputStream(), contentType)
            } finally {
                if (enableAuthz) ecfi.getExecutionContext().getArtifactExecution().enableAuthz()
            }

            if (logger.infoEnabled) logger.info("Finished XSL-FO request to ${pathInfoList}, content type ${response.getContentType()} in ${System.currentTimeMillis()-startTime}ms; session ${request.session.id} thread ${Thread.currentThread().id}:${Thread.currentThread().name}")
        } catch (ArtifactAuthorizationException e) {
            // SC_UNAUTHORIZED 401 used when authc/login fails, use SC_FORBIDDEN 403 for authz failures
            // See ScreenRenderImpl.checkWebappSettings for authc and SC_UNAUTHORIZED handling
            logger.warn((String) "Web Access Forbidden (no authz): " + e.message)
            response.sendError(HttpServletResponse.SC_FORBIDDEN, e.message)
        } catch (ArtifactTarpitException e) {
            logger.warn((String) "Web Too Many Requests (tarpit): " + e.message)
            if (e.getRetryAfterSeconds()) response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            // NOTE: there is no constant on HttpServletResponse for 429; see RFC 6585 for details
            response.sendError(429, e.message)
        } catch (ScreenResourceNotFoundException e) {
            logger.warn((String) "Web Resource Not Found: " + e.message)
            response.sendError(HttpServletResponse.SC_NOT_FOUND, e.message)
        } catch (Throwable t) {
            logger.error("Error transforming XSL-FO content:\n${xslFoText}", t)
            if (ec.message.hasError()) {
                String errorsString = ec.message.errorsString
                logger.error(errorsString, t)
                if (!response.isCommitted()) sendDocumentRefused(response, ec.message.getErrors())
                else response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorsString)
            } else if (!response.isCommitted()) {
                // no message to show (e.g. a document with no pages): still a readable page, the details are in the log above
                sendDocumentRefused(response, ["The document could not be produced. Please contact Aspen Support if this keeps happening."])
            } else {
                throw t
            }
        } finally {
            // make sure everything is cleaned up
            ec.destroy()
        }
    }

    /** A document that can't be produced answers with a small readable page naming why (the messages its screen actions
     * added), never a bare HTTP 500 error page. 422: the request was understood but this document can't be made from it. */
    protected static void sendDocumentRefused(HttpServletResponse response, List<String> errors) {
        response.reset()
        response.setStatus(422)
        response.setContentType("text/html")
        response.setCharacterEncoding("UTF-8")
        StringBuilder sb = new StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>This document can't be printed</title></head>")
        sb.append("<body style=\"font-family: -apple-system, 'Segoe UI', Helvetica, Arial, sans-serif; margin: 40px;\">")
        sb.append("<h3>This document can't be printed</h3><ul>")
        for (String err in errors) sb.append("<li>").append(StringUtilities.encodeForXmlAttribute(err ?: "")).append("</li>")
        sb.append("</ul><p><button type=\"button\" onclick=\"if (history.length &gt; 1) { history.back(); } else { window.close(); }\">Go Back</button></p>")
        sb.append("</body></html>")
        // the output stream, not a writer: on the transform-failure path the stream may already be the one in use
        response.getOutputStream().write(sb.toString().getBytes("UTF-8"))
    }
}
