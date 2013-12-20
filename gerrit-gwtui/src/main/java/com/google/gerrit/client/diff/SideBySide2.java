// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.client.diff;

import com.google.gerrit.client.Gerrit;
import com.google.gerrit.client.account.DiffPreferences;
import com.google.gerrit.client.change.ChangeScreen2;
import com.google.gerrit.client.changes.ChangeApi;
import com.google.gerrit.client.changes.ChangeInfo;
import com.google.gerrit.client.changes.ChangeInfo.RevisionInfo;
import com.google.gerrit.client.changes.ChangeList;
import com.google.gerrit.client.diff.DiffInfo.Region;
import com.google.gerrit.client.diff.DiffInfo.Span;
import com.google.gerrit.client.diff.LineMapper.LineOnOtherInfo;
import com.google.gerrit.client.diff.PaddingManager.LinePaddingWidgetWrapper;
import com.google.gerrit.client.diff.PaddingManager.PaddingWidgetWrapper;
import com.google.gerrit.client.patches.PatchUtil;
import com.google.gerrit.client.projects.ConfigInfoCache;
import com.google.gerrit.client.rpc.CallbackGroup;
import com.google.gerrit.client.rpc.GerritCallback;
import com.google.gerrit.client.rpc.RestApi;
import com.google.gerrit.client.rpc.ScreenLoadCallback;
import com.google.gerrit.client.ui.Screen;
import com.google.gerrit.common.PageLinks;
import com.google.gerrit.common.changes.ListChangesOption;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArray;
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.RepeatingCommand;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.dom.client.Style;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyPressEvent;
import com.google.gwt.event.logical.shared.ResizeEvent;
import com.google.gwt.event.logical.shared.ResizeHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwtexpui.globalkey.client.GlobalKey;
import com.google.gwtexpui.globalkey.client.KeyCommand;
import com.google.gwtexpui.globalkey.client.KeyCommandSet;
import com.google.gwtexpui.globalkey.client.ShowHelpCommand;

import net.codemirror.lib.CodeMirror;
import net.codemirror.lib.CodeMirror.BeforeSelectionChangeHandler;
import net.codemirror.lib.CodeMirror.GutterClickHandler;
import net.codemirror.lib.CodeMirror.LineClassWhere;
import net.codemirror.lib.CodeMirror.LineHandle;
import net.codemirror.lib.CodeMirror.RenderLineHandler;
import net.codemirror.lib.CodeMirror.Viewport;
import net.codemirror.lib.Configuration;
import net.codemirror.lib.KeyMap;
import net.codemirror.lib.LineCharacter;
import net.codemirror.lib.LineWidget;
import net.codemirror.lib.ModeInjector;
import net.codemirror.lib.Rect;
import net.codemirror.lib.TextMarker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SideBySide2 extends Screen {
  interface Binder extends UiBinder<FlowPanel, SideBySide2> {}
  private static final Binder uiBinder = GWT.create(Binder.class);

  private static final JsArrayString EMPTY =
      JavaScriptObject.createArray().cast();

  @UiField(provided = true)
  Header header;

  @UiField(provided = true)
  DiffTable diffTable;

  private final Change.Id changeId;
  private final PatchSet.Id base;
  private final PatchSet.Id revision;
  private final String path;
  private final DisplaySide startSide;
  private final int startLine;
  private DiffPreferences prefs;

  private CodeMirror cmA;
  private CodeMirror cmB;
  private HandlerRegistration resizeHandler;
  private DiffInfo diff;
  private boolean largeFile;
  private LineMapper mapper;
  private List<TextMarker> markers;
  private List<Runnable> undoLineClass;
  private CommentManager commentManager;
  private SkipManager skipManager;
  private Map<LineHandle, LinePaddingWidgetWrapper> linePaddingOnOtherSideMap;
  private List<DiffChunkInfo> diffChunks;

  private KeyCommandSet keysNavigation;
  private KeyCommandSet keysAction;
  private KeyCommandSet keysComment;
  private List<HandlerRegistration> handlers;
  private List<Runnable> deferred;
  private PreferencesAction prefsAction;
  private int reloadVersionId;

  public SideBySide2(
      PatchSet.Id base,
      PatchSet.Id revision,
      String path,
      DisplaySide startSide,
      int startLine) {
    this.base = base;
    this.revision = revision;
    this.changeId = revision.getParentKey();
    this.path = path;
    this.startSide = startSide;
    this.startLine = startLine;

    prefs = DiffPreferences.create(Gerrit.getAccountDiffPreference());
    handlers = new ArrayList<HandlerRegistration>(6);
    // TODO: Re-implement necessary GlobalKey bindings.
    addDomHandler(GlobalKey.STOP_PROPAGATION, KeyPressEvent.getType());
    keysNavigation = new KeyCommandSet(Gerrit.C.sectionNavigation());
    header = new Header(keysNavigation, base, revision, path);
    diffTable = new DiffTable(this, base, revision, path);
    add(uiBinder.createAndBindUi(this));
  }

  @Override
  protected void onInitUI() {
    super.onInitUI();
    setHeaderVisible(false);
  }

  @Override
  protected void onLoad() {
    super.onLoad();

    CallbackGroup cmGroup = new CallbackGroup();
    CodeMirror.initLibrary(cmGroup.add(CallbackGroup.<Void> emptyCallback()));
    final CallbackGroup group = new CallbackGroup();
    final AsyncCallback<Void> modeInjectorCb =
        group.add(CallbackGroup.<Void> emptyCallback());

    DiffApi.diff(revision, path)
      .base(base)
      .wholeFile()
      .intraline(prefs.intralineDifference())
      .ignoreWhitespace(prefs.ignoreWhitespace())
      .get(cmGroup.addFinal(new GerritCallback<DiffInfo>() {
        @Override
        public void onSuccess(DiffInfo diffInfo) {
          diff = diffInfo;
          if (prefs.syntaxHighlighting()) {
            largeFile = isLargeFile(diffInfo);
            if (largeFile) {
              modeInjectorCb.onSuccess(null);
            } else {
              injectMode(diffInfo, modeInjectorCb);
            }
          } else {
            modeInjectorCb.onSuccess(null);
          }
        }
      }));

    final CommentsCollections comments = new CommentsCollections();
    comments.load(base, revision, path, group);

    RestApi call = ChangeApi.detail(changeId.get());
    ChangeList.addOptions(call, EnumSet.of(
        ListChangesOption.ALL_REVISIONS));
    call.get(group.add(new GerritCallback<ChangeInfo>() {
      @Override
      public void onSuccess(ChangeInfo info) {
        info.revisions().copyKeysIntoChildren("name");
        JsArray<RevisionInfo> list = info.revisions().values();
        RevisionInfo.sortRevisionInfoByNumber(list);
        diffTable.setUpPatchSetNav(list, diff);
        header.setChangeInfo(info);
      }}));

    ConfigInfoCache.get(changeId, group.addFinal(
        new ScreenLoadCallback<ConfigInfoCache.Entry>(SideBySide2.this) {
          @Override
          protected void preDisplay(ConfigInfoCache.Entry result) {
            commentManager = new CommentManager(
                SideBySide2.this,
                base, revision, path,
                result.getCommentLinkProcessor());
            setTheme(result.getTheme());
            display(comments);
          }
        }));
  }

  @Override
  public void onShowView() {
    super.onShowView();
    Window.enableScrolling(false);
    if (prefs.hideTopMenu()) {
      Gerrit.setHeaderVisible(false);
    }
    resizeHandler = Window.addResizeHandler(new ResizeHandler() {
      @Override
      public void onResize(ResizeEvent event) {
        resizeCodeMirror();
      }
    });

    final int height = getCodeMirrorHeight();
    operation(new Runnable() {
      @Override
      public void run() {
        cmA.setHeight(height);
        cmB.setHeight(height);
        cmA.refresh();
        cmB.refresh();
      }
    });
    diffTable.sidePanel.adjustGutters(cmB);

    if (startSide != null && startLine > 0) {
      int line = startLine - 1;
      CodeMirror cm = getCmFromSide(startSide);
      if (cm.lineAtHeight(height - 20) < line) {
        cm.scrollToY(cm.heightAtLine(line, "local") - 0.5 * height);
      }
      cm.setCursor(LineCharacter.create(line));
      cm.focus();
    } else if (diff.meta_b() != null) {
      int line = 0;
      if (!diffChunks.isEmpty()) {
        DiffChunkInfo d = diffChunks.get(0);
        CodeMirror cm = getCmFromSide(d.getSide());
        line = d.getStart();
        if (cm.lineAtHeight(height - 20) < line) {
          cm.scrollToY(cm.heightAtLine(line, "local") - 0.5 * height);
        }
      }
      cmB.setCursor(LineCharacter.create(line));
      cmB.focus();
    } else {
      cmA.setCursor(LineCharacter.create(0));
      cmA.focus();
    }
    if (Gerrit.isSignedIn() && prefs.autoReview()) {
      header.autoReview();
    }
    prefetchNextFile();
  }

  @Override
  protected void onUnload() {
    super.onUnload();

    removeKeyHandlerRegistrations();
    if (commentManager != null) {
      commentManager.saveAllDrafts(null);
    }
    if (resizeHandler != null) {
      resizeHandler.removeHandler();
      resizeHandler = null;
    }
    if (cmA != null) {
      cmA.getWrapperElement().removeFromParent();
    }
    if (cmB != null) {
      cmB.getWrapperElement().removeFromParent();
    }
    if (prefsAction != null) {
      prefsAction.hide();
    }

    Window.enableScrolling(true);
    Gerrit.setHeaderVisible(true);
  }

  private void removeKeyHandlerRegistrations() {
    for (HandlerRegistration h : handlers) {
      h.removeHandler();
    }
    handlers.clear();
  }

  private void registerCmEvents(final CodeMirror cm) {
    cm.on("beforeSelectionChange", onSelectionChange(cm));
    cm.on("cursorActivity", updateActiveLine(cm));
    cm.on("gutterClick", onGutterClick(cm));
    cm.on("renderLine", resizeLinePadding(cm.side()));
    cm.on("viewportChange", adjustGutters(cm));
    cm.on("focus", new Runnable() {
      @Override
      public void run() {
        updateActiveLine(cm).run();
      }
    });
    cm.addKeyMap(KeyMap.create()
        .on("'a'", upToChange(true))
        .on("'u'", upToChange(false))
        .on("[", header.navigate(Direction.PREV))
        .on("]", header.navigate(Direction.NEXT))
        .on("'r'", header.toggleReviewed())
        .on("'o'", commentManager.toggleOpenBox(cm))
        .on("Enter", commentManager.toggleOpenBox(cm))
        .on("'c'", commentManager.insertNewDraft(cm))
        .on("N", maybeNextVimSearch(cm))
        .on("P", diffChunkNav(cm, Direction.PREV))
        .on("Shift-O", commentManager.openClosePublished(cm))
        .on("Shift-Left", moveCursorToSide(cm, DisplaySide.A))
        .on("Shift-Right", moveCursorToSide(cm, DisplaySide.B))
        .on("'i'", new Runnable() {
          public void run() {
            switch (getIntraLineStatus()) {
              case OFF:
              case OK:
                toggleShowIntraline();
                break;
              default:
                break;
            }
          }
        })
        .on("','", new Runnable() {
          @Override
          public void run() {
            prefsAction.show();
          }
        })
        .on("Shift-/", new Runnable() {
          @Override
          public void run() {
            new ShowHelpCommand().onKeyPress(null);
          }
        })
        .on("Space", new Runnable() {
          @Override
          public void run() {
            CodeMirror.handleVimKey(cm, "<PageDown>");
          }
        })
        .on("Ctrl-A", new Runnable() {
          @Override
          public void run() {
            cm.execCommand("selectAll");
          }
        }));
  }

  private BeforeSelectionChangeHandler onSelectionChange(final CodeMirror cm) {
    return new BeforeSelectionChangeHandler() {
      private Image icon;

      @Override
      public void handle(CodeMirror cm, LineCharacter anchor, LineCharacter head) {
        if (anchor == head
            || (anchor.getLine() == head.getLine()
             && anchor.getCh() == head.getCh())) {
          if (icon != null) {
            icon.setVisible(false);
          }
          return;
        } else if (icon == null) {
          init(anchor);
        }

        icon.setVisible(true);
        Rect r = cm.charCoords(head, "local");
        Style s = icon.getElement().getStyle();
        s.setTop((int) (r.top() - icon.getOffsetHeight() + 2), Unit.PX);
        s.setLeft((int) (r.right() - icon.getOffsetWidth() / 2), Unit.PX);
      }

      private void init(LineCharacter anchor) {
        icon = new Image(Gerrit.RESOURCES.draftComments());
        icon.setTitle(PatchUtil.C.commentInsert());
        icon.setStyleName(DiffTable.style.insertCommentIcon());
        icon.addClickHandler(new ClickHandler() {
          @Override
          public void onClick(ClickEvent event) {
            icon.setVisible(false);
            commentManager.insertNewDraft(cm).run();
          }
        });
        add(icon);
        cm.addWidget(anchor, icon.getElement(), false);
      }
    };
  }

  @Override
  public void registerKeys() {
    super.registerKeys();

    keysNavigation.add(new UpToChangeCommand2(revision, 0, 'u'));
    keysNavigation.add(
        new NoOpKeyCommand(0, 'j', PatchUtil.C.lineNext()),
        new NoOpKeyCommand(0, 'k', PatchUtil.C.linePrev()));
    keysNavigation.add(
        new NoOpKeyCommand(0, 'n', PatchUtil.C.chunkNext2()),
        new NoOpKeyCommand(0, 'p', PatchUtil.C.chunkPrev2()));

    keysAction = new KeyCommandSet(Gerrit.C.sectionActions());
    keysAction.add(new NoOpKeyCommand(0, KeyCodes.KEY_ENTER,
        PatchUtil.C.expandComment()));
    keysAction.add(new NoOpKeyCommand(0, 'o', PatchUtil.C.expandComment()));
    keysAction.add(new NoOpKeyCommand(
        KeyCommand.M_SHIFT, 'o', PatchUtil.C.expandAllCommentsOnCurrentLine()));
    keysAction.add(new KeyCommand(0, 'r', PatchUtil.C.toggleReviewed()) {
      @Override
      public void onKeyPress(KeyPressEvent event) {
        header.toggleReviewed().run();
      }
    });
    keysAction.add(new KeyCommand(0, 'a', PatchUtil.C.openReply()) {
      @Override
      public void onKeyPress(KeyPressEvent event) {
        upToChange(true).run();
      }
    });
    keysAction.add(new KeyCommand(0, ',', PatchUtil.C.showPreferences()) {
      @Override
      public void onKeyPress(KeyPressEvent event) {
        prefsAction.show();
      }
    });
    if (getIntraLineStatus() == DiffInfo.IntraLineStatus.OFF
        || getIntraLineStatus() == DiffInfo.IntraLineStatus.OK) {
      keysAction.add(new KeyCommand(0, 'i', PatchUtil.C.toggleIntraline()) {
        @Override
        public void onKeyPress(KeyPressEvent event) {
          toggleShowIntraline();
        }
      });
    }

    if (Gerrit.isSignedIn()) {
      keysAction.add(new NoOpKeyCommand(0, 'c', PatchUtil.C.commentInsert()));
      keysComment = new KeyCommandSet(PatchUtil.C.commentEditorSet());
      keysComment.add(new NoOpKeyCommand(KeyCommand.M_CTRL, 's',
          PatchUtil.C.commentSaveDraft()));
      keysComment.add(new NoOpKeyCommand(0, KeyCodes.KEY_ESCAPE,
          PatchUtil.C.commentCancelEdit()));
    } else {
      keysComment = null;
    }
    removeKeyHandlerRegistrations();
    handlers.add(GlobalKey.add(this, keysNavigation));
    if (keysComment != null) {
      handlers.add(GlobalKey.add(this, keysComment));
    }
    handlers.add(GlobalKey.add(this, keysAction));
  }

  private void display(final CommentsCollections comments) {
    setShowTabs(prefs.showTabs());
    setShowIntraline(prefs.intralineDifference());
    if (prefs.showLineNumbers()) {
      diffTable.addStyleName(DiffTable.style.showLineNumbers());
    }

    cmA = newCM(diff.meta_a(), diff.text_a(), DisplaySide.A, diffTable.cmA);
    cmB = newCM(diff.meta_b(), diff.text_b(), DisplaySide.B, diffTable.cmB);
    skipManager = new SkipManager(this, commentManager);

    operation(new Runnable() {
      public void run() {
        // Estimate initial CM3 height, fixed up in onShowView.
        int height = Window.getClientHeight()
            - (Gerrit.getHeaderFooterHeight() + 18);
        cmA.setHeight(height);
        cmB.setHeight(height);

        render(diff);
        commentManager.render(comments);
        if (prefs.expandAllComments()) {
          commentManager.setExpandAllComments(true);
        }
        skipManager.render(prefs.context(), diff);
      }
    });

    registerCmEvents(cmA);
    registerCmEvents(cmB);
    new ScrollSynchronizer(diffTable, cmA, cmB, mapper);

    prefsAction = new PreferencesAction(this, prefs);
    header.init(prefsAction);

    if (largeFile && prefs.syntaxHighlighting()) {
      Scheduler.get().scheduleFixedDelay(new RepeatingCommand() {
        @Override
        public boolean execute() {
          if (prefs.syntaxHighlighting() && isAttached()) {
            setSyntaxHighlighting(prefs.syntaxHighlighting());
          }
          return false;
        }
      }, 250);
    }
  }

  private CodeMirror newCM(
      DiffInfo.FileMeta meta,
      String contents,
      DisplaySide side,
      Element parent) {
    Configuration cfg = Configuration.create()
      .set("readOnly", true)
      .set("cursorBlinkRate", 0)
      .set("cursorHeight", 0.85)
      .set("lineNumbers", prefs.showLineNumbers())
      .set("tabSize", prefs.tabSize())
      .set("mode", largeFile ? null : getContentType(meta))
      .set("lineWrapping", false)
      .set("styleSelectedText", true)
      .set("showTrailingSpace", prefs.showWhitespaceErrors())
      .set("keyMap", "vim_ro")
      .set("value", meta != null ? contents : "");
    return CodeMirror.create(side, parent, cfg);
  }

  DiffInfo.IntraLineStatus getIntraLineStatus() {
    return diff.intraline_status();
  }

  void setShowTabs(boolean b) {
    if (b) {
      diffTable.addStyleName(DiffTable.style.showTabs());
    } else {
      diffTable.removeStyleName(DiffTable.style.showTabs());
    }
  }

  void setShowLineNumbers(boolean b) {
    cmA.setOption("lineNumbers", b);
    cmB.setOption("lineNumbers", b);
    if (b) {
      diffTable.addStyleName(DiffTable.style.showLineNumbers());
    } else {
      diffTable.removeStyleName(DiffTable.style.showLineNumbers());
    }
  }

  void setShowIntraline(boolean b) {
    if (b && getIntraLineStatus() == DiffInfo.IntraLineStatus.OFF) {
      reloadDiffInfo();
    } else if (b) {
      diffTable.removeStyleName(DiffTable.style.noIntraline());
    } else {
      diffTable.addStyleName(DiffTable.style.noIntraline());
    }
  }

  private void toggleShowIntraline() {
    prefs.intralineDifference(!prefs.intralineDifference());
    setShowIntraline(prefs.intralineDifference());
    prefsAction.update();
  }

  void setSyntaxHighlighting(boolean b) {
    if (b) {
      injectMode(diff, new AsyncCallback<Void>() {
        @Override
        public void onSuccess(Void result) {
          if (prefs.syntaxHighlighting()) {
            cmA.setOption("mode", getContentType(diff.meta_a()));
            cmB.setOption("mode", getContentType(diff.meta_b()));
          }
        }

        @Override
        public void onFailure(Throwable caught) {
          prefs.syntaxHighlighting(false);
        }
      });
    } else {
      cmA.setOption("mode", (String) null);
      cmB.setOption("mode", (String) null);
    }
  }

  void setContext(final int context) {
    operation(new Runnable() {
      @Override
      public void run() {
        skipManager.removeAll();
        skipManager.render(context, diff);
      }
    });
  }

  private void render(DiffInfo diff) {
    JsArray<Region> regions = diff.content();

    header.setNoDiff(regions.length() == 0
        || (regions.length() == 1 && regions.get(0).ab() != null));

    mapper = new LineMapper();
    markers = new ArrayList<TextMarker>();
    undoLineClass = new ArrayList<Runnable>();
    linePaddingOnOtherSideMap = new HashMap<LineHandle, LinePaddingWidgetWrapper>();
    diffChunks = new ArrayList<DiffChunkInfo>();

    String diffColor = diff.meta_a() == null || diff.meta_b() == null
        ? DiffTable.style.intralineBg()
        : DiffTable.style.diff();

    for (int i = 0; i < regions.length(); i++) {
      Region current = regions.get(i);
      int origLineA = mapper.getLineA();
      int origLineB = mapper.getLineB();
      if (current.ab() != null) { // Common
        mapper.appendCommon(current.ab().length());
      } else { // Insert, Delete or Edit
        JsArrayString currentA = current.a() == null ? EMPTY : current.a();
        JsArrayString currentB = current.b() == null ? EMPTY : current.b();
        int aLength = currentA.length();
        int bLength = currentB.length();
        String color = currentA == EMPTY || currentB == EMPTY
            ? diffColor
            : DiffTable.style.intralineBg();
        colorLines(cmA, color, origLineA, aLength);
        colorLines(cmB, color, origLineB, bLength);
        int commonCnt = Math.min(aLength, bLength);
        mapper.appendCommon(commonCnt);
        if (aLength < bLength) { // Edit with insertion
          int insertCnt = bLength - aLength;
          mapper.appendInsert(insertCnt);
        } else if (aLength > bLength) { // Edit with deletion
          int deleteCnt = aLength - bLength;
          mapper.appendDelete(deleteCnt);
        }
        int chunkEndA = mapper.getLineA() - 1;
        int chunkEndB = mapper.getLineB() - 1;
        if (aLength > 0) {
          addDiffChunkAndPadding(cmB, chunkEndB, chunkEndA, aLength, bLength > 0);
        }
        if (bLength > 0) {
          addDiffChunkAndPadding(cmA, chunkEndA, chunkEndB, bLength, aLength > 0);
        }
        markEdit(cmA, currentA, current.edit_a(), origLineA);
        markEdit(cmB, currentB, current.edit_b(), origLineB);
        if (aLength == 0) {
          diffTable.sidePanel.addGutter(cmB, origLineB, SidePanel.GutterType.INSERT);
        } else if (bLength == 0) {
          diffTable.sidePanel.addGutter(cmA, origLineA, SidePanel.GutterType.DELETE);
        } else {
          diffTable.sidePanel.addGutter(cmB, origLineB, SidePanel.GutterType.EDIT);
        }
      }
    }
  }

  private void clearMarkers() {
    if (markers != null) {
      for (TextMarker m : markers) {
        m.clear();
      }
      markers = null;
    }
    if (undoLineClass != null) {
      for (Runnable r : undoLineClass) {
        r.run();
      }
      undoLineClass = null;
    }
    if (linePaddingOnOtherSideMap != null) {
      for (LinePaddingWidgetWrapper x : linePaddingOnOtherSideMap.values()) {
        x.getWidget().clear();
      }
      linePaddingOnOtherSideMap = null;
    }
  }

  CodeMirror otherCm(CodeMirror me) {
    return me == cmA ? cmB : cmA;
  }

  CodeMirror getCmFromSide(DisplaySide side) {
    return side == DisplaySide.A ? cmA : cmB;
  }

  LineOnOtherInfo lineOnOther(DisplaySide side, int line) {
    return mapper.lineOnOther(side, line);
  }

  private void markEdit(CodeMirror cm, JsArrayString lines,
      JsArray<Span> edits, int startLine) {
    if (edits == null) {
      return;
    }
    EditIterator iter = new EditIterator(lines, startLine);
    Configuration intralineBgOpt = Configuration.create()
        .set("className", DiffTable.style.intralineBg())
        .set("readOnly", true);
    Configuration diffOpt = Configuration.create()
        .set("className", DiffTable.style.diff())
        .set("readOnly", true);
    LineCharacter last = CodeMirror.pos(0, 0);
    for (int i = 0; i < edits.length(); i++) {
      Span span = edits.get(i);
      LineCharacter from = iter.advance(span.skip());
      LineCharacter to = iter.advance(span.mark());
      int fromLine = from.getLine();
      if (last.getLine() == fromLine) {
        markers.add(cm.markText(last, from, intralineBgOpt));
      } else {
        markers.add(cm.markText(CodeMirror.pos(fromLine, 0), from, intralineBgOpt));
      }
      markers.add(cm.markText(from, to, diffOpt));
      last = to;
      colorLines(cm, LineClassWhere.BACKGROUND,
          DiffTable.style.diff(),
          fromLine, to.getLine());
    }
  }

  private void colorLines(CodeMirror cm, String color, int line, int cnt) {
    colorLines(cm, LineClassWhere.WRAP, color, line, line + cnt);
  }

  private void colorLines(final CodeMirror cm, final LineClassWhere where,
      final String className, final int start, final int end) {
    if (start < end) {
      for (int line = start; line < end; line++) {
        cm.addLineClass(line, where, className);
      }
      undoLineClass.add(new Runnable() {
        @Override
        public void run() {
          for (int line = start; line < end; line++) {
            cm.removeLineClass(line, where, className);
          }
        }
      });
    }
  }

  private void addDiffChunkAndPadding(CodeMirror cmToPad, int lineToPad,
      int lineOnOther, int chunkSize, boolean edit) {
    CodeMirror otherCm = otherCm(cmToPad);
    linePaddingOnOtherSideMap.put(otherCm.getLineHandle(lineOnOther),
        new LinePaddingWidgetWrapper(addPaddingWidget(cmToPad,
            lineToPad, 0, Unit.EM, null), lineToPad, chunkSize));
    diffChunks.add(new DiffChunkInfo(otherCm.side(),
        lineOnOther - chunkSize + 1, lineOnOther, edit));
  }

  PaddingWidgetWrapper addPaddingWidget(CodeMirror cm,
      int line, double height, Unit unit, Integer index) {
    SimplePanel padding = new SimplePanel();
    padding.setStyleName(DiffTable.style.padding());
    padding.getElement().getStyle().setHeight(height, unit);
    Configuration config = Configuration.create()
        .set("coverGutter", true)
        .set("above", line == -1)
        .set("noHScroll", true);
    if (index != null) {
      config = config.set("insertAt", index);
    }
    LineWidget widget = addLineWidget(cm, line == -1 ? 0 : line, padding, config);
    return new PaddingWidgetWrapper(widget, padding.getElement());
  }

  /**
   * A LineWidget needs to be added to diffTable in order to respond to browser
   * events, but CodeMirror doesn't render the widget until the containing line
   * is scrolled into viewportMargin, causing it to appear at the bottom of the
   * DOM upon loading. Fix by hiding the widget until it is first scrolled into
   * view (when CodeMirror fires a "redraw" event on the widget).
   */
  LineWidget addLineWidget(CodeMirror cm, int line,
      final Widget widget, Configuration options) {
    widget.setVisible(false);
    LineWidget lineWidget = cm.addLineWidget(line, widget.getElement(), options);
    lineWidget.onFirstRedraw(new Runnable() {
      @Override
      public void run() {
        widget.setVisible(true);
      }
    });
    return lineWidget;
  }

  private void clearActiveLine(CodeMirror cm) {
    if (cm.hasActiveLine()) {
      LineHandle activeLine = cm.getActiveLine();
      cm.removeLineClass(activeLine,
          LineClassWhere.WRAP, DiffTable.style.activeLine());
      cm.setActiveLine(null);
    }
  }

  private Runnable adjustGutters(final CodeMirror cm) {
    return new Runnable() {
      @Override
      public void run() {
        Viewport fromTo = cm.getViewport();
        int size = fromTo.getTo() - fromTo.getFrom() + 1;
        if (cm.getOldViewportSize() == size) {
          return;
        }
        cm.setOldViewportSize(size);
        diffTable.sidePanel.adjustGutters(cmB);
      }
    };
  }

  private Runnable updateActiveLine(final CodeMirror cm) {
    final CodeMirror other = otherCm(cm);
    return new Runnable() {
      public void run() {
        /**
         * The rendering of active lines has to be deferred. Reflow
         * caused by adding and removing styles chokes Firefox when arrow
         * key (or j/k) is held down. Performance on Chrome is fine
         * without the deferral.
         */
        defer(new Runnable() {
          @Override
          public void run() {
            LineHandle handle = cm.getLineHandleVisualStart(
                cm.getCursor("end").getLine());
            if (cm.hasActiveLine() && cm.getActiveLine().equals(handle)) {
              return;
            }

            clearActiveLine(cm);
            clearActiveLine(other);
            cm.setActiveLine(handle);
            cm.addLineClass(
                handle, LineClassWhere.WRAP, DiffTable.style.activeLine());
            LineOnOtherInfo info =
                mapper.lineOnOther(cm.side(), cm.getLineNumber(handle));
            if (info.isAligned()) {
              LineHandle oLineHandle = other.getLineHandle(info.getLine());
              other.setActiveLine(oLineHandle);
              other.addLineClass(oLineHandle, LineClassWhere.WRAP,
                  DiffTable.style.activeLine());
            }
          }
        });
      }
    };
  }

  private GutterClickHandler onGutterClick(final CodeMirror cm) {
    return new GutterClickHandler() {
      @Override
      public void handle(CodeMirror instance, int line, String gutter,
          NativeEvent clickEvent) {
        if (clickEvent.getButton() == NativeEvent.BUTTON_LEFT
            && !clickEvent.getMetaKey()
            && !clickEvent.getAltKey()
            && !clickEvent.getCtrlKey()
            && !clickEvent.getShiftKey()) {
          if (!(cm.hasActiveLine() &&
              cm.getLineNumber(cm.getActiveLine()) == line)) {
            cm.setCursor(LineCharacter.create(line));
          }
          Scheduler.get().scheduleDeferred(new ScheduledCommand() {
            @Override
            public void execute() {
              commentManager.insertNewDraft(cm).run();
            }
          });
        }
      }
    };
  }

  private Runnable upToChange(final boolean openReplyBox) {
    return new Runnable() {
      public void run() {
        CallbackGroup group = new CallbackGroup();
        commentManager.saveAllDrafts(group);
        group.addFinal(new GerritCallback<Void>() {
          @Override
          public void onSuccess(Void result) {
            String b = base != null ? String.valueOf(base.get()) : null;
            String rev = String.valueOf(revision.get());
            Gerrit.display(
              PageLinks.toChange(changeId, rev),
              new ChangeScreen2(changeId, b, rev, openReplyBox));
          }
        }).onSuccess(null);
      }
    };
  }

  private Runnable moveCursorToSide(final CodeMirror cmSrc, DisplaySide sideDst) {
    final CodeMirror cmDst = getCmFromSide(sideDst);
    if (cmDst == cmSrc) {
      return new Runnable() {
        @Override
        public void run() {
        }
      };
    }

    final DisplaySide sideSrc = cmSrc.side();
    return new Runnable() {
      public void run() {
        if (cmSrc.hasActiveLine()) {
          cmDst.setCursor(LineCharacter.create(mapper.lineOnOther(
              sideSrc,
              cmSrc.getLineNumber(cmSrc.getActiveLine())).getLine()));
        }
        cmDst.focus();
      }
    };
  }

  private Runnable maybeNextVimSearch(final CodeMirror cm) {
    return new Runnable() {
      @Override
      public void run() {
        if (cm.hasVimSearchHighlight()) {
          CodeMirror.handleVimKey(cm, "n");
        } else {
          diffChunkNav(cm, Direction.NEXT).run();
        }
      }
    };
  }

  private Runnable diffChunkNav(final CodeMirror cm, final Direction dir) {
    return new Runnable() {
      @Override
      public void run() {
        int line = cm.hasActiveLine() ? cm.getLineNumber(cm.getActiveLine()) : 0;
        int res = Collections.binarySearch(
                diffChunks,
                new DiffChunkInfo(cm.side(), line, 0, false),
                getDiffChunkComparator());
        if (res < 0) {
          res = -res - (dir == Direction.PREV ? 1 : 2);
        }
        res = res + (dir == Direction.PREV ? -1 : 1);
        if (res < 0 || diffChunks.size() <= res) {
          return;
        }

        DiffChunkInfo lookUp = diffChunks.get(res);
        // If edit, skip the deletion chunk and set focus on the insertion one.
        if (lookUp.isEdit() && lookUp.getSide() == DisplaySide.A) {
          res = res + (dir == Direction.PREV ? -1 : 1);
          if (res < 0 || diffChunks.size() <= res) {
            return;
          }
        }

        DiffChunkInfo target = diffChunks.get(res);
        CodeMirror targetCm = getCmFromSide(target.getSide());
        targetCm.setCursor(LineCharacter.create(target.getStart()));
        targetCm.focus();
        targetCm.scrollToY(
            targetCm.heightAtLine(target.getStart(), "local") -
            0.5 * cmB.getScrollbarV().getClientHeight());
      }
    };
  }

  /**
   * Diff chunks are ordered by their starting lines. If it's a deletion,
   * use its corresponding line on the revision side for comparison. In
   * the edit case, put the deletion chunk right before the insertion chunk.
   * This placement guarantees well-ordering.
   */
  private Comparator<DiffChunkInfo> getDiffChunkComparator() {
    return new Comparator<DiffChunkInfo>() {
      @Override
      public int compare(DiffChunkInfo o1, DiffChunkInfo o2) {
        if (o1.getSide() == o2.getSide()) {
          return o1.getStart() - o2.getStart();
        } else if (o1.getSide() == DisplaySide.A) {
          int comp = mapper.lineOnOther(o1.getSide(), o1.getStart())
              .getLine() - o2.getStart();
          return comp == 0 ? -1 : comp;
        } else {
          int comp = o1.getStart() -
              mapper.lineOnOther(o2.getSide(), o2.getStart()).getLine();
          return comp == 0 ? 1 : comp;
        }
      }
    };
  }

  DiffChunkInfo getDiffChunk(DisplaySide side, int line) {
    int res = Collections.binarySearch(
        diffChunks,
        new DiffChunkInfo(side, line, 0, false), // Dummy DiffChunkInfo
        getDiffChunkComparator());
    if (res >= 0) {
      return diffChunks.get(res);
    } else { // The line might be within a DiffChunk
      res = -res - 1;
      if (res > 0) {
        DiffChunkInfo info = diffChunks.get(res - 1);
        if (info.getSide() == side && info.getStart() <= line &&
            line <= info.getEnd()) {
          return info;
        }
      }
    }
    return null;
  }

  void defer(Runnable thunk) {
    if (deferred == null) {
      final ArrayList<Runnable> list = new ArrayList<Runnable>();
      deferred = list;
      Scheduler.get().scheduleDeferred(new ScheduledCommand() {
        @Override
        public void execute() {
          deferred = null;
          operation(new Runnable() {
            public void run() {
              for (Runnable thunk : list) {
                thunk.run();
              }
            }
          });
        }
      });
    }
    deferred.add(thunk);
  }

  void resizePaddingOnOtherSide(DisplaySide mySide, int line) {
    CodeMirror cm = getCmFromSide(mySide);
    LineHandle handle = cm.getLineHandle(line);
    final LinePaddingWidgetWrapper otherWrapper = linePaddingOnOtherSideMap.get(handle);
    double myChunkHeight = cm.heightAtLine(line + 1) -
        cm.heightAtLine(line - otherWrapper.getChunkLength() + 1);
    Element otherPadding = otherWrapper.getElement();
    int otherPaddingHeight = otherPadding.getOffsetHeight();
    CodeMirror otherCm = otherCm(cm);
    int otherLine = otherWrapper.getOtherLine();
    LineHandle other = otherCm.getLineHandle(otherLine);
    if (linePaddingOnOtherSideMap.containsKey(other)) {
      LinePaddingWidgetWrapper myWrapper = linePaddingOnOtherSideMap.get(other);
      Element myPadding = linePaddingOnOtherSideMap.get(other).getElement();
      int myPaddingHeight = myPadding.getOffsetHeight();
      myChunkHeight -= myPaddingHeight;
      double otherChunkHeight = otherCm.heightAtLine(otherLine + 1) -
          otherCm.heightAtLine(otherLine - myWrapper.getChunkLength() + 1) -
          otherPaddingHeight;
      double delta = myChunkHeight - otherChunkHeight;
      if (delta > 0) {
        if (myPaddingHeight != 0) {
          myPadding.getStyle().setHeight((double) 0, Unit.PX);
          myWrapper.getWidget().changed();
        }
        if (otherPaddingHeight != delta) {
          otherPadding.getStyle().setHeight(delta, Unit.PX);
          otherWrapper.getWidget().changed();
        }
      } else {
        if (myPaddingHeight != -delta) {
          myPadding.getStyle().setHeight(-delta, Unit.PX);
          myWrapper.getWidget().changed();
        }
        if (otherPaddingHeight != 0) {
          otherPadding.getStyle().setHeight((double) 0, Unit.PX);
          otherWrapper.getWidget().changed();
        }
      }
    } else if (otherPaddingHeight != myChunkHeight) {
      otherPadding.getStyle().setHeight(myChunkHeight, Unit.PX);
      otherWrapper.getWidget().changed();
    }
  }

  // TODO: Maybe integrate this with PaddingManager.
  private RenderLineHandler resizeLinePadding(final DisplaySide side) {
    return new RenderLineHandler() {
      @Override
      public void handle(final CodeMirror instance, final LineHandle handle,
          Element element) {
        commentManager.resizePadding(handle);
        if (linePaddingOnOtherSideMap.containsKey(handle)) {
          defer(new Runnable() {
            @Override
            public void run() {
              resizePaddingOnOtherSide(side, instance.getLineNumber(handle));
            }
          });
        }
      }
    };
  }

  void resizeCodeMirror() {
    int height = getCodeMirrorHeight();
    cmA.setHeight(height);
    cmB.setHeight(height);
    diffTable.sidePanel.adjustGutters(cmB);
  }

  private int getCodeMirrorHeight() {
    int rest = Gerrit.getHeaderFooterHeight()
        + header.getOffsetHeight()
        + diffTable.getHeaderHeight()
        + 5; // Estimate
    return Window.getClientHeight() - rest;
  }

  private String getContentType(DiffInfo.FileMeta meta) {
    return prefs.syntaxHighlighting()
          && meta != null
          && meta.content_type() != null
        ? ModeInjector.getContentType(meta.content_type())
        : null;
  }

  private void injectMode(DiffInfo diffInfo, AsyncCallback<Void> cb) {
    new ModeInjector()
      .add(getContentType(diffInfo.meta_a()))
      .add(getContentType(diffInfo.meta_b()))
      .inject(cb);
  }

  DiffPreferences getPrefs() {
    return prefs;
  }

  CommentManager getCommentManager() {
    return commentManager;
  }

  void operation(final Runnable apply) {
    cmA.operation(new Runnable() {
      @Override
      public void run() {
        cmB.operation(new Runnable() {
          @Override
          public void run() {
            apply.run();
          }
        });
      }
    });
  }

  private void prefetchNextFile() {
    String nextPath = header.getNextPath();
    if (nextPath != null) {
      DiffApi.diff(revision, nextPath)
        .base(base)
        .wholeFile()
        .intraline(prefs.intralineDifference())
        .ignoreWhitespace(prefs.ignoreWhitespace())
        .get(new AsyncCallback<DiffInfo>() {
          @Override
          public void onSuccess(DiffInfo info) {
            new ModeInjector()
              .add(getContentType(info.meta_a()))
              .add(getContentType(info.meta_b()))
              .inject(CallbackGroup.<Void> emptyCallback());
          }

          @Override
          public void onFailure(Throwable caught) {
          }
        });
    }
  }

  void reloadDiffInfo() {
    final int id = ++reloadVersionId;
    DiffApi.diff(revision, path)
      .base(base)
      .wholeFile()
      .intraline(prefs.intralineDifference())
      .ignoreWhitespace(prefs.ignoreWhitespace())
      .get(new GerritCallback<DiffInfo>() {
        @Override
        public void onSuccess(DiffInfo diffInfo) {
          if (id == reloadVersionId && isAttached()) {
            diff = diffInfo;
            operation(new Runnable() {
              @Override
              public void run() {
                skipManager.removeAll();
                clearMarkers();
                diffTable.sidePanel.clearDiffGutters();
                setShowIntraline(prefs.intralineDifference());
                render(diff);
                skipManager.render(prefs.context(), diff);
              }
            });
          }
        }
      });
  }

  private static boolean isLargeFile(DiffInfo diffInfo) {
    return (diffInfo.meta_a() != null && diffInfo.meta_a().lines() > 500)
        || (diffInfo.meta_b() != null && diffInfo.meta_b().lines() > 500);
  }
}
