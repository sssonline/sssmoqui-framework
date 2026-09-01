/* Copyright 2014-2017 Spokane Software Systems, Inc. All Rights Reserved. */
package org.moqui.impl.webapp

import groovy.transform.CompileStatic
import org.apache.poi.hssf.util.HSSFColor
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ArtifactTarpitException
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.screen.ScreenRender
import org.moqui.util.StringUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.ServletException
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.xml.transform.stream.StreamSource

import groovy.json.JsonSlurperClassic

import java.io.FileOutputStream
import java.io.IOException
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.*

@CompileStatic
class MoquiPoiServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiPoiServlet.class)

    MoquiPoiServlet() {
        super()
    }

    @Override
    void doPost(HttpServletRequest request, HttpServletResponse response) { doScreenRequest(request, response) }

    @Override
    void doGet(HttpServletRequest request, HttpServletResponse response) { doScreenRequest(request, response) }

    void doScreenRequest(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        ExecutionContextFactoryImpl ecfi =
                (ExecutionContextFactoryImpl) getServletContext().getAttribute("executionContextFactory")
        String moquiWebappName = getServletContext().getInitParameter("moqui-name")

        if (ecfi == null || moquiWebappName == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "System is initializing, try again soon.")
            return
        }

        long startTime = System.currentTimeMillis()

        if (logger.traceEnabled) logger.trace("Start request to [${request.getPathInfo()}] at time [${startTime}] in session [${request.session.id}] thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")

        ExecutionContextImpl activeEc = ecfi.activeContext.get()
        if (activeEc != null) {
            logger.warn("In MoquiServlet.service there is already an ExecutionContext for user ${activeEc.user.username} (from ${activeEc.forThreadId}:${activeEc.forThreadName}) in this thread (${Thread.currentThread().id}:${Thread.currentThread().name}), destroying")
            activeEc.destroy()
        }
        ExecutionContextImpl ec = ecfi.getEci()

        String jsonText = null
        try {
            ec.initWebFacade(moquiWebappName, request, response)
            ec.web.requestAttributes.put("moquiRequestStartTime", startTime)
            ArrayList<String> pathInfoList = ec.web.getPathInfoList()

            ScreenRender sr = ec.screen.makeRender().webappName(moquiWebappName).renderMode("json")
                    .rootScreenFromHost(request.getServerName()).screenPath(pathInfoList)
            // NOTE: For the time being, can't figure out how to get these square brackets into the ftl file, so have to manually add them here.
            jsonText = '[' + sr.render() + ']'

            def omitHeaderRow = ((ec.web.parameters.get("omitHeaderRow") as String) ?: '') == 'on'

            def slurper = new groovy.json.JsonSlurperClassic()
            def lists = (ArrayList)slurper.parseText(jsonText)

            XSSFWorkbook workbook = new XSSFWorkbook()

            // Styles used throughout
            byte[] rgb = new byte[3]; rgb[0] = 0; rgb[1] = 0; rgb[2] = (byte)136 // Dark Blue
            XSSFColor headingColor = new XSSFColor(rgb, new DefaultIndexedColorMap())
            XSSFFont headingFont = workbook.createFont()
            headingFont.setBold(true)
            headingFont.setColor(headingColor)
            XSSFCellStyle headingStyle = workbook.createCellStyle()
            headingStyle.setWrapText(true)
            headingStyle.setFont(headingFont)
            headingStyle.setAlignment(HorizontalAlignment.CENTER)
            headingStyle.setBorderBottom(BorderStyle.THIN)

            XSSFCellStyle dataNumberStyle = workbook.createCellStyle()
            dataNumberStyle.setAlignment(HorizontalAlignment.RIGHT)
            XSSFCellStyle dataCurrencyStyle = workbook.createCellStyle()
            dataCurrencyStyle.setAlignment(HorizontalAlignment.RIGHT)
            dataCurrencyStyle.setDataFormat((short)8) // $#,##0.00_);[Red]($#,##0.00)

            for( int i = 0; i < lists.size(); i++ ) {
                // TODO: If the title is blank, default to a generic title
                // TODO: Probably skip if there are no columns.
                def title   = lists[i]["title"].toString().replaceAll(/\s+/, ' ')
                def columns = (ArrayList)lists[i]["columns"]
                def data    = (ArrayList)lists[i]["data"]
                // per-column type hints from the json render ("text"/"auto"), aligned with columns;
                // null when the producer doesn't emit them — value sniffing then decides alone (card #943)
                def colTypes = (ArrayList)lists[i]["colTypes"]

                // EXPERIMENTAL:
                // If the number of columns and data do not match, likely it is being caused
                // by control columns that just have simple buttons in them (edit, delete, etc).
                // Attempt to intelligently remove them here.
                if(columns && data && columns.size() != ((ArrayList)data[0]).size()) {
                    if (logger.infoEnabled) logger.info("Mismatch detected between header size and data size. Attempting to intelligently omit control button columns...");
                    if (logger.infoEnabled) logger.info("Columns: ${columns.size()}")
                    if (logger.infoEnabled) logger.info("Data   : ${((ArrayList)data[0]).size()}")

                    def trimmedCols = new ArrayList<String>()
                    // colTypes is positionally aligned with columns, so it must be trimmed in lockstep
                    def trimmedTypes = colTypes != null ? new ArrayList<String>() : null
                    for( int c = 0; c < columns.size(); c++ ) {
                        def colHeading = columns[c].toString()
                        if( colHeading != "New" &&
                            colHeading != "Add" &&
                            colHeading != "Create" &&
                            colHeading != "Find" &&
                            colHeading != "Search" &&
                            colHeading != "Update" &&
                            colHeading != "Edit" &&
                            colHeading != "Delete") {
                            trimmedCols.add(colHeading)
                            if( trimmedTypes != null && c < colTypes.size() ) trimmedTypes.add(colTypes[c].toString())
                        }
                    }
                    columns = trimmedCols
                    if( trimmedTypes != null ) colTypes = trimmedTypes
                }

                // Create a new sheet for this list
                XSSFSheet sheet = workbook.createSheet(title)

                if(!omitHeaderRow) {
                    // Create the column headings
                    XSSFRow headings = sheet.createRow(0)

                    for( int c = 0; c < columns.size(); c++ ) {
                        def colHeading = columns[c].toString()

                        // If the column names are long, try to intelligently wrap them at roughly the middle
                        def colLen = colHeading.length()
                        if(colLen > 10 ) {
                            def midPoint = (int)(colLen/2)
                            // First space after the mid-point
                            def firstAfter = colHeading.indexOf(' ', midPoint)
                            def firstBefore = colHeading.substring(0, midPoint).lastIndexOf(' ')
                            def splitPoint = -1
                            if( firstAfter > 0 && firstBefore > 0 ) {
                                if( (midPoint-firstBefore) < (firstAfter-midPoint) && firstBefore > 2 ) splitPoint = firstBefore
                                else splitPoint = firstAfter
                            }
                            else if( firstAfter > 0 ) splitPoint = firstAfter
                            else if( firstBefore > 0 ) splitPoint = firstBefore

                            // Avoid splitting "Id"
                            if( splitPoint > 2 && (colLen-splitPoint) > 3 ) {
                                colHeading = colHeading.substring(0, splitPoint) + "\n" + colHeading.substring(splitPoint+1)
                            }
                        }


                        XSSFCell cell = headings.createCell(c)
                        cell.setCellValue(colHeading)
                        cell.setCellStyle(headingStyle)
                    }
                    // Freeze the top row for easy scrolling
                    sheet.createFreezePane(0, 1)
                }

                // Write the data
                if(data) {
                    // Attempt to identify columns that are all numbers/currency or blank for right alignment and formatting
                    def isNum = []
                    for( int c = 0; c < columns.size(); c++ ) { isNum.push(true) }
                    // NOTE: I realise this only accounts for USD...I don't care.
                    //       Also, values are 0 (not currency), 1 (positive currency) or -1 (negative currency)
                    def isCurrency = []
                    for( int c = 0; c < columns.size(); c++ ) { isCurrency.push(true) }

                    for( int d = 0; d < data.size(); d++ ) {
                        def rowData = (ArrayList)data[d]
                        def anyTrue = false
                        for( int c = 0; c < rowData.size(); c++ ) {
                            def cell = rowData[c].toString().replaceAll(/[, ]/,'')
                            isNum[c] = isNum[c] && (cell.isNumber() || cell == "")
                            // If not a number, check for currency format; an empty cell is tolerated the
                            // same way isNum tolerates it — one blank row shouldn't demote a currency
                            // column to text (it did, and the null-shift fix now makes blanks explicit)
                            if( !isNum[c] ) {
                                isCurrency[c] = isCurrency[c] && (cell == "" || cell.length() > 2 &&
                                                (cell.substring(0, 1) == '$' && cell.substring(1).isNumber() ||
                                                 (cell.substring(0, 2) == '($' || cell.substring(0, 2) == '$(') &&
                                                 cell.substring(cell.length()-1) == ')' &&
                                                 cell.substring(2, cell.length()-1).isNumber()))
                            }
                            anyTrue = anyTrue || isNum[c] || isCurrency[c]
                        }
                        if( !anyTrue ) break
                    }
                    // Declared "text" columns are identifiers (invoice/order/trouble numbers etc): they
                    // must export as text no matter what their values sniff as, so the sheet matches the
                    // screen and pasted ids still match text-keyed lookups (card #943)
                    if( colTypes != null ) {
                        for( int c = 0; c < colTypes.size() && c < isNum.size(); c++ ) {
                            if( colTypes[c].toString() == "text" ) { isNum[c] = false; isCurrency[c] = false }
                        }
                    }
                    for( int d = 0; d < data.size(); d++ ) {
                        XSSFRow curRow = sheet.createRow(d + (omitHeaderRow ? 0 : 1))
                        def rowData = (ArrayList)data[d]
                        for( int c = 0; c < rowData.size(); c++ ) {
                            XSSFCell cell = curRow.createCell(c)
                            def cellVal = rowData[c].toString()
                            if( isNum[c] ) {
                                cellVal = cellVal.replaceAll(/[, ]/,'')
                                cell.setCellStyle(dataNumberStyle)
                                if( cellVal != '' ) {
                                    // isLong not isInteger: an all-digit value past 2^31 fell through to
                                    // toDouble() and rendered in scientific notation
                                    cell.setCellValue(cellVal.isLong() ? cellVal.toLong() : cellVal.toDouble())
                                }
                            }
                            else if( isCurrency[c] ) {
                                int sign = (cellVal.indexOf('(') > -1) ? -1 : 1
                                cellVal = cellVal.replaceAll(/[, \(\)\$]/,'')
                                cell.setCellStyle(dataCurrencyStyle)
                                if( cellVal != '' ) {
                                    cellVal = sign * (cellVal.isInteger() ? cellVal.toInteger() : cellVal.toDouble())
                                    cell.setCellValue(cellVal)
                                }
                            }
                            else {
                                cell.setCellValue(cellVal)
                            }
                        }
                    }
                }

                // Autosize the columns
                for( int c = 0; c < columns.size(); c++ ) {
                    sheet.autoSizeColumn(c)
                }

            }

            // Send the file to the client
            String contentType = (String) ec.web.requestParameters."contentType" ?: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            response.setContentType(contentType)

            String filename = (ec.web.parameters.get("filename") as String) ?: (ec.web.parameters.get("saveFilename") as String)
            if (filename) {
                String utfFilename = StringUtilities.encodeAsciiFilename(filename)
                response.addHeader("Content-Disposition", "attachment; filename=\"${filename}\"; filename*=utf-8''${utfFilename}")
            } else {
                response.addHeader("Content-Disposition", "inline")
            }

            workbook.write(response.getOutputStream())

            if (logger.infoEnabled) logger.info("Finished POI request to ${pathInfoList}, content type ${response.getContentType()} in ${System.currentTimeMillis()-startTime}ms; session ${request.session.id} thread ${Thread.currentThread().id}:${Thread.currentThread().name}")
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
            logger.error("Error transforming POI content:\n${jsonText}", t)
            if (ec.message.hasError()) {
                String errorsString = ec.message.errorsString
                logger.error(errorsString, t)
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorsString)
            } else {
                throw t
            }
        } finally {
            // make sure everything is cleaned up
            ec.destroy()
        }
    }
}
