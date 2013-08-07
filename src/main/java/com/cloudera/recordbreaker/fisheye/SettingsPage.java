/*
 * Copyright (c) 2012, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */
package com.cloudera.recordbreaker.fisheye;

import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.IAjaxIndicatorAware;
import org.apache.wicket.ajax.markup.html.form.AjaxButton;
import org.apache.wicket.markup.html.WebPage;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextArea;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.form.RequiredTextField;
import org.apache.wicket.markup.html.form.PasswordTextField;
import org.apache.wicket.markup.html.panel.FeedbackPanel;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.model.Model;
import org.apache.wicket.model.StringResourceModel;
import org.apache.wicket.model.PropertyModel;
import org.apache.wicket.model.CompoundPropertyModel;
import org.apache.wicket.util.value.ValueMap;

import com.cloudera.recordbreaker.analyzer.DataQuery;
import com.cloudera.recordbreaker.analyzer.CrawlSummary;
import com.cloudera.recordbreaker.analyzer.CrawlRuntimeStatus;

import java.net.URI;
import java.util.List;
import java.io.IOException;
import java.net.URISyntaxException;

/************************************************
 * Wicket Page class that allows user to edit Settings
 *
 * @author "Michael Cafarella"
 * @version 1.0
 * @since 1.0
 * @see WebPage
 *************************************************/
public class SettingsPage extends WebPage {
  static String HDFS_PROTOCOL = "hdfs://";
  static String LOCALFS_PROTOCOL = "file://";

  /////////////////////////////////////////////////////
  // User login form
  ////////////////////////////////////////////////////
  public final class LoginForm extends Form<ValueMap> {
    public LoginForm(final String id, ValueMap vm) {
      super(id, new CompoundPropertyModel<ValueMap>(vm));
      add(new RequiredTextField<String>("loginusername").setType(String.class));
      add(new PasswordTextField("loginpassword").setType(String.class));
      
      add(new AjaxButton("submitbutton") {
          protected void onSubmit(final AjaxRequestTarget target, final Form form) {
            loginErrorMsgDisplay.setVisibilityAllowed(false);            
            target.add(loginErrorMsgDisplay);
          }
          protected void onError(final AjaxRequestTarget target, final Form form) {
            loginErrorMsgDisplay.setVisibilityAllowed(true);            
            target.add(loginErrorMsgDisplay);
          }
        });
    }
    public void onSubmit() {
      FishEye fe = FishEye.getInstance();
      AccessController accessCtrl = fe.getAccessController();
      
      ValueMap vals = getModelObject();
      if (accessCtrl.login((String) vals.get("loginusername"), (String) vals.get("loginpassword"))) {
        vals.put("currentuser", (String) vals.get("loginusername"));

        loginErrorMsgDisplay.setVisibilityAllowed(false);
        setResponsePage(new SettingsPage());
      } else {
        loginErrorMsgDisplay.setVisibilityAllowed(true);        
      }
      vals.put("loginpassword", "");
    }
    public void onError() {
      ValueMap vals = getModelObject();
      loginErrorMsgDisplay.setVisibilityAllowed(true);
      vals.put("loginpassword", "");      
    }
    public void onConfigure() {
      FishEye fe = FishEye.getInstance();
      AccessController accessCtrl = fe.getAccessController();
      setVisibilityAllowed(accessCtrl.getCurrentUser() == null);
    }
  }

  /////////////////////////////////////////////////////
  // User logout form
  ////////////////////////////////////////////////////
  public final class LogoutForm extends Form<ValueMap> {
    public LogoutForm(final String id, ValueMap vm) {
      super(id, new CompoundPropertyModel<ValueMap>(vm));
      add(new Label("currentuser"));
    }
    public void onSubmit() {
      FishEye fe = FishEye.getInstance();
      AccessController accessCtrl = fe.getAccessController();
      
      accessCtrl.logout();
      setResponsePage(new SettingsPage());      
    }
    public void onConfigure() {
      FishEye fe = FishEye.getInstance();      
      AccessController accessCtrl = fe.getAccessController();      
      setVisibilityAllowed(accessCtrl.getCurrentUser() != null);
    }
  }

  /////////////////////////////////////////////////////
  // Filesystem registration form
  ////////////////////////////////////////////////////
  public final class FilesystemRegistrationForm extends Form<ValueMap> {
    public FilesystemRegistrationForm(final String id, ValueMap vm) {
      super(id, new CompoundPropertyModel<ValueMap>(vm));
      add(new TextField<String>("hdfsDir").setType(String.class));
      add(new TextField<String>("localfsDir").setType(String.class));      
      add(new AjaxButton("fssubmitbutton") {
          protected void onSubmit(final AjaxRequestTarget target, final Form form) {
            fsErrorMsgDisplay.setVisibilityAllowed(false);            
            target.add(fsErrorMsgDisplay);
          }
          protected void onError(final AjaxRequestTarget target, final Form form) {
            fsErrorMsgDisplay.setVisibilityAllowed(true);            
            target.add(fsErrorMsgDisplay);
          }
        });
    }
    public void onSubmit() {
      String hdfsUrl = (String) getModelObject().get("hdfsDir");
      String fsUrl = (String) getModelObject().get("localfsDir");      
      FishEye fe = FishEye.getInstance();
      ValueMap vals = getModelObject();      
      boolean success = false;
      URI targetURI = null;
      try {
        if (hdfsUrl != null && hdfsUrl.length() > 0) {
          if (! hdfsUrl.startsWith(HDFS_PROTOCOL)) {
            hdfsUrl = HDFS_PROTOCOL + hdfsUrl;
          }
          try {
            targetURI = new URI(hdfsUrl);
          } catch (URISyntaxException use) {
            // REMIND -- mjc -- Need to communicate error back to user
            use.printStackTrace();
          }
        } else if (fsUrl != null && fsUrl.length() > 0) {
          if (! fsUrl.startsWith(LOCALFS_PROTOCOL)) {
            fsUrl = LOCALFS_PROTOCOL + fsUrl;            
          }
          try {
            targetURI = new URI(fsUrl);
          } catch (URISyntaxException use) {
            // REMIND -- mjc -- Need to communicate error back to user
            use.printStackTrace();
          }
        }
        if (targetURI != null) {
          success = fe.registerAndCrawlFilesystem(targetURI);
        } else {
          success = false;
        }
      } catch (IOException ioe) {
      }

      if (success) {
        vals.put("currentfs", targetURI.toString());
        fsErrorMsgDisplay.setVisibilityAllowed(false);
        setResponsePage(new SettingsPage());
      } else {
        // Ask user to kindly error message
        fsErrorMsgDisplay.setVisibilityAllowed(true);
      }
    }
    public void onError() {
      ValueMap vals = getModelObject();
      fsErrorMsgDisplay.setVisibilityAllowed(true);
      vals.put("currentfs", "");      
    }
    public void onConfigure() {
      setVisibilityAllowed(FishEye.getInstance().getFSURI() == null);
    }
  }

  /////////////////////////////////////////////////////
  // Filesystem info/cancellation form
  ////////////////////////////////////////////////////
  public final class FilesystemInfoForm extends Form<ValueMap> {
    public FilesystemInfoForm(final String id, ValueMap vm) {
      super(id, new CompoundPropertyModel<ValueMap>(vm));

      //
      // Info about the currently-registered filesystem
      //
      add(new Label("currentfs", new Model<String>() {
            public String getObject() {
              URI fsuri = FishEye.getInstance().getFSURI();
              if (fsuri == null) {
                return "";
              } else {
                return fsuri.toString();
              }
            }
      }));
      add(new Label("numcompletedcrawls", new Model<String>() {
            public String getObject() {
              List<CrawlSummary> crawlList = FishEye.getInstance().getAnalyzer().getCrawlSummaries();
              int numCompletedCrawls = 0;
              for (CrawlSummary cs: crawlList) {
                if (! cs.isOngoing) {
                  numCompletedCrawls++;
                }
              }
              return "" + numCompletedCrawls;
            }
      }));
      add(new Label("numongoingcrawls", new Model<String>() {
            public String getObject() {
              List<CrawlSummary> crawlList = FishEye.getInstance().getAnalyzer().getCrawlSummaries();
              int numOngoingCrawls = 0;
              for (CrawlSummary cs: crawlList) {
                if (cs.isOngoing) {
                  numOngoingCrawls++;
                }
              }
              return "" + numOngoingCrawls;
            }
      }));

      //
      // Info about the currently-running crawl
      //
      add(new WebMarkupContainer("currentCrawlInfo") {
          {
            setOutputMarkupPlaceholderTag(true);
            setVisibilityAllowed(false);
            add(new Label("numDone", new Model<String>() {
                  public String getObject() {
                    FishEye fe = FishEye.getInstance();            
                    if (fe.checkOngoingCrawl() != null) {
                      return "" + fe.checkOngoingCrawl().getNumDone();
                    }
                    return null;
                  }
            }));
            add(new Label("numToProcess", new Model<String>() {
                  public String getObject() {
                    FishEye fe = FishEye.getInstance();                        
                    if (fe.checkOngoingCrawl() != null) {
                      return "" + fe.checkOngoingCrawl().getNumToProcess();
                    } else {
                      return null;
                    }
                  }
            }));
            add(new Label("crawlStatusMessage", new Model<String>() {
                  public String getObject() {
                    FishEye fe = FishEye.getInstance();                        
                    if (fe.checkOngoingCrawl() != null) {
                      return "" + fe.checkOngoingCrawl().getMessage();
                    } else {
                      return "";
                    }
                  }
            }));
          }
          public void onConfigure() {
            setVisibilityAllowed(FishEye.getInstance().checkOngoingCrawl() != null);
          }
        });
    }
    public void onSubmit() {
      FishEye fe = FishEye.getInstance();
      fe.cancelFS();
      setResponsePage(new SettingsPage());      
    }
    public void onConfigure() {
      setVisibilityAllowed(FishEye.getInstance().getFSURI() != null);
    }
  }

  ///////////////////////////////////////////////////
  // Hive query server form
  //////////////////////////////////////////////////
  public final class QueryServerInfoForm extends Form<ValueMap> {
    public QueryServerInfoForm(final String id, ValueMap vm) {
      super(id, new CompoundPropertyModel<ValueMap>(vm));

      //
      // Info about the query server status
      //
      add(new Label("queryserverloc", new Model<String>() {
            public String getObject() {
              return DataQuery.getInstance().getHiveConnectionString();
            }
      }));
      add(new Label("queryserverstatus", new Model<String>() {
            public String getObject() {
              return FishEye.getInstance().isQueryServerAvailable(false) ? "available" : "not available";
            }
      }));
    }
    public void onSubmit() {
      FishEye.getInstance().isQueryServerAvailable(true);
      setResponsePage(new SettingsPage());      
    }
    public void onConfigure() {
      setVisibilityAllowed(true);
    }
  }

  final WebMarkupContainer loginErrorMsgDisplay = new WebMarkupContainer("loginErrorMsgContainer");
  final WebMarkupContainer fsErrorMsgDisplay = new WebMarkupContainer("fsErrorMsgContainer");
  public SettingsPage() {
    FishEye fe = FishEye.getInstance();
    AccessController accessCtrl = fe.getAccessController();
    final String username = accessCtrl.getCurrentUser();
    final ValueMap logins = new ValueMap();    
    logins.put("currentuser", username);
    this.setOutputMarkupPlaceholderTag(true);        

    //
    // Login/logout
    //
    add(new LoginForm("loginform", logins));
    add(new LogoutForm("logoutform", logins));
    final Label loginErrorLabel = new Label("loginErrorMessage", "Your username and password did not match.");
    loginErrorMsgDisplay.add(loginErrorLabel);
    loginErrorMsgDisplay.setOutputMarkupPlaceholderTag(true);
    add(loginErrorMsgDisplay);
    loginErrorMsgDisplay.setVisibilityAllowed(false);

    //
    // Add filesystem/remove filesystem
    //
    final ValueMap fsinfo = new ValueMap();        
    add(new FilesystemRegistrationForm("fsaddform", fsinfo));
    add(new FilesystemInfoForm("fsinfoform", fsinfo));
    final Label fsErrorLabel = new Label("fsErrorMessage", "The filesystem was not found.");
    fsErrorMsgDisplay.add(fsErrorLabel);
    fsErrorMsgDisplay.setOutputMarkupPlaceholderTag(true);
    add(fsErrorMsgDisplay);
    fsErrorMsgDisplay.setVisibilityAllowed(false);

    //
    // Hive query server info
    //
    add(new QueryServerInfoForm("queryserverinfo", new ValueMap()));

    //
    // If the filesystem is there, we need to have info about its crawls
    //
    /**
    WebMarkupContainer crawlContainer = new WebMarkupContainer("crawlContainer");
    ListView<CrawlSummary> crawlListView = new ListView<CrawlSummary>("crawlListView", crawlList) {
      protected void populateItem(ListItem<CrawlSummary> item) {
        CrawlSummary cs = item.getModelObject();
        // Fields are: 'crawlid' and 'crawllastexamined'
        item.add(new Label("crawlid", "" + cs.getCrawlId()));
        item.add(new Label("crawllastexamined", cs.getLastExamined()));
      }
    };
    crawlContainer.add(crawlListView);
    fsDisplayContainer.add(crawlContainer);
    crawlContainer.setVisibilityAllowed(crawlList.size() > 0);
    **/

    //
    // Standard environment variables
    //
    add(new Label("fisheyeStarttime", fe.getStartTime().toString()));
    add(new Label("fisheyePort", "" + fe.getPort()));
    try {
      add(new Label("fisheyeDir", "" + fe.getFisheyeDir().getCanonicalPath()));
    } catch (IOException iex) {
      add(new Label("fisheyeDir", "unknown"));
    }
  }
}
