/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.support.LongSparseIntArray;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;

import java.util.ArrayList;
import java.util.HashMap;

import com.sexteragram.messenger.ExteraConfig;

public class ChatAttachAlertContactsLayout extends ChatAttachAlert.AttachAlertLayout implements NotificationCenter.NotificationCenterDelegate {

    private FrameLayout frameLayout;
    private RecyclerListView listView;
    private FillLastLinearLayoutManager layoutManager;
    private ShareAdapter listAdapter;
    private ShareSearchAdapter searchAdapter;
    private EmptyTextProgressView emptyView;
    private View shadow;
    private AnimatorSet shadowAnimation;
    private SearchField searchField;

    private boolean ignoreLayout;

    private PhonebookShareAlertDelegate delegate;

    public interface PhonebookShareAlertDelegate {
        void didSelectContact(TLRPC.User user, boolean notify, int scheduleDate);
    }

    public static class UserCell extends FrameLayout {

        private final Theme.ResourcesProvider resourcesProvider;
        private BackupImageView avatarImageView;
        private SimpleTextView nameTextView;
        private SimpleTextView statusTextView;

        private AvatarDrawable avatarDrawable;
        private TLRPC.User currentUser;
        private int currentId;

        private CharSequence currentName;
        private CharSequence currentStatus;
        private TLRPC.User formattedPhoneNumberUser;
        private CharSequence formattedPhoneNumber;

        private String lastName;
        private int lastStatus;
        private TLRPC.FileLocation lastAvatar;

        private int currentAccount = UserConfig.selectedAccount;

        private boolean needDivider;

        public UserCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;

            avatarDrawable = new AvatarDrawable(resourcesProvider);

            avatarImageView = new BackupImageView(context);
            avatarImageView.setRoundRadius(ExteraConfig.getAvatarCorners(46));
            addView(avatarImageView, LayoutHelper.createFrame(46, 46, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 0 : 14, 9, LocaleController.isRTL ? 14 : 0, 0));

            nameTextView = new SimpleTextView(context) {
                @Override
                public boolean setText(CharSequence value, boolean force) {
                    value = Emoji.replaceEmoji(value, getPaint().getFontMetricsInt(), AndroidUtilities.dp(14), false);
                    return super.setText(value, force);
                }
            };
            NotificationCenter.listenEmojiLoading(nameTextView);
            nameTextView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            nameTextView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            nameTextView.setTextSize(16);
            nameTextView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP);
            addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 20, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 28 : 72, 12, LocaleController.isRTL ? 72 : 28, 0));

            statusTextView = new SimpleTextView(context);
            statusTextView.setTextSize(13);
            statusTextView.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
            statusTextView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP);
            addView(statusTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 20, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 28 : 72, 36, LocaleController.isRTL ? 72 : 28, 0));
        }

        public void setCurrentId(int id) {
            currentId = id;
        }

        public void setData(TLRPC.User user, CharSequence name, CharSequence status, boolean divider) {
            if (user == null && name == null && status == null) {
                currentStatus = null;
                currentName = null;
                nameTextView.setText("");
                statusTextView.setText("");
                avatarImageView.setImageDrawable(null);
                return;
            }
            currentStatus = status;
            currentName = name;
            currentUser = user;
            needDivider = divider;
            setWillNotDraw(!needDivider);
            update(0);
        }

        public interface CharSequenceCallback {
            CharSequence run();
        }

        public void setData(TLRPC.User user, CharSequence name, CharSequenceCallback status, boolean divider) {
            setData(user, name, (CharSequence) null, divider);
            Utilities.globalQueue.postRunnable(() -> {
                final CharSequence newCurrentStatus = status.run();
                AndroidUtilities.runOnUIThread(() -> {
                    setStatus(newCurrentStatus);
                });
            });
        }

        public void setStatus(CharSequence status) {
            currentStatus = status;
            if (currentStatus != null) {
                statusTextView.setText(currentStatus);
            } else if (currentUser != null) {
                if (TextUtils.isEmpty(currentUser.phone)) {
                    statusTextView.setText(LocaleController.getString("NumberUnknown", R.string.NumberUnknown));
                } else {
                    if (formattedPhoneNumberUser != currentUser && formattedPhoneNumber != null) {
                        statusTextView.setText(formattedPhoneNumber);
                    } else {
                        statusTextView.setText("");
                        Utilities.globalQueue.postRunnable(() -> {
                            if (currentUser != null) {
                                formattedPhoneNumber = PhoneFormat.getInstance().format("+" + currentUser.phone);
                                formattedPhoneNumberUser = currentUser;
                                AndroidUtilities.runOnUIThread(() -> statusTextView.setText(formattedPhoneNumber));
                            }
                        });
                    }
                }
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY)
            );
        }

        public void update(int mask) {
            TLRPC.FileLocation photo = null;
            String newName = null;
            if (currentUser != null && currentUser.photo != null) {
                photo = currentUser.photo.photo_small;
            }

            if (mask != 0) {
                boolean continueUpdate = false;
                if ((mask & MessagesController.UPDATE_MASK_AVATAR) != 0) {
                    if (lastAvatar != null && photo == null || lastAvatar == null && photo != null || lastAvatar != null && photo != null && (lastAvatar.volume_id != photo.volume_id || lastAvatar.local_id != photo.local_id)) {
                        continueUpdate = true;
                    }
                }
                if (currentUser != null && !continueUpdate && (mask & MessagesController.UPDATE_MASK_STATUS) != 0) {
                    int newStatus = 0;
                    if (currentUser.status != null) {
                        newStatus = currentUser.status.expires;
                    }
                    if (newStatus != lastStatus) {
                        continueUpdate = true;
                    }
                }
                if (!continueUpdate && currentName == null && lastName != null && (mask & MessagesController.UPDATE_MASK_NAME) != 0) {
                    if (currentUser != null) {
                        newName = UserObject.getUserName(currentUser);
                    }
                    if (!newName.equals(lastName)) {
                        continueUpdate = true;
                    }
                }
                if (!continueUpdate) {
                    return;
                }
            }

            if (currentUser != null) {
                avatarDrawable.setInfo(currentUser);
                if (currentUser.status != null) {
                    lastStatus = currentUser.status.expires;
                } else {
                    lastStatus = 0;
                }
            } else if (currentName != null) {
                avatarDrawable.setInfo(currentId, currentName.toString(), null);
            } else {
                avatarDrawable.setInfo(currentId, "#", null);
            }

            if (currentName != null) {
                lastName = null;
                nameTextView.setText(currentName);
            } else {
                if (currentUser != null) {
                    lastName = newName == null ? UserObject.getUserName(currentUser) : newName;
                } else {
                    lastName = "";
                }
                nameTextView.setText(lastName);
            }

            setStatus(currentStatus);

            lastAvatar = photo;
            if (currentUser != null) {
                avatarImageView.setForUserOrChat(currentUser, avatarDrawable);
            } else {
                avatarImageView.setImageDrawable(avatarDrawable);
            }
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (needDivider && !ExteraConfig.disableDividers) {
                canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(70), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(70) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }

        protected int getThemedColor(int key) {
            return Theme.getColor(key, resourcesProvider);
        }
    }

    public ChatAttachAlertContactsLayout(ChatAttachAlert alert, Context context, Theme.ResourcesProvider resourcesProvider) {
        super(alert, context, resourcesProvider);

        searchAdapter = new ShareSearchAdapter(context);

        frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));

        searchField = new SearchField(context, false, resourcesProvider) {
            @Override
            public void onTextChange(String text) {
                if (text.length() != 0) {
                    if (emptyView != null) {
                        emptyView.setText(LocaleController.getString("NoResult", R.string.NoResult));
                    }
                } else {
                    if (listView.getAdapter() != listAdapter) {
                        int top = getCurrentTop();
                        emptyView.setText(LocaleController.getString("NoContacts", R.string.NoContacts));
                        emptyView.showTextView();
                        listView.setAdapter(listAdapter);
                        listAdapter.notifyDataSetChanged();
                        if (top > 0) {
                            layoutManager.scrollToPositionWithOffset(0, -top);
                        }
                    }
                }
                if (searchAdapter != null) {
                    searchAdapter.search(text);
                }
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                parentAlert.makeFocusable(getSearchEditText(), true);
                return super.onInterceptTouchEvent(ev);
            }

            @Override
            public void processTouchEvent(MotionEvent event) {
                MotionEvent e = MotionEvent.obtain(event);
                e.setLocation(e.getRawX(), e.getRawY() - parentAlert.getSheetContainer().getTranslationY() - AndroidUtilities.dp(58));
                listView.dispatchTouchEvent(e);
                e.recycle();
            }

            @Override
            protected void onFieldTouchUp(EditTextBoldCursor editText) {
                parentAlert.makeFocusable(editText, true);
            }
        };
        searchField.setHint(LocaleController.getString("SearchFriends", R.string.SearchFriends));
        frameLayout.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));

        emptyView = new EmptyTextProgressView(context, null, resourcesProvider);
        emptyView.showTextView();
        emptyView.setText(LocaleController.getString("NoContacts", R.string.NoContacts));
        addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 0, 52, 0, 0));

        listView = new RecyclerListView(context, resourcesProvider) {
            @Override
            protected boolean allowSelectChildAtPosition(float x, float y) {
                return y >= parentAlert.scrollOffsetY[0] + AndroidUtilities.dp(30) + (!parentAlert.inBubbleMode ? AndroidUtilities.statusBarHeight : 0);
            }
        };
        listView.setClipToPadding(false);
        listView.setLayoutManager(layoutManager = new FillLastLinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false, AndroidUtilities.dp(9), listView) {
            @Override
            public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
                LinearSmoothScroller linearSmoothScroller = new LinearSmoothScroller(recyclerView.getContext()) {
                    @Override
                    public int calculateDyToMakeVisible(View view, int snapPreference) {
                        int dy = super.calculateDyToMakeVisible(view, snapPreference);
                        dy -= (listView.getPaddingTop() - AndroidUtilities.dp(8));
                        return dy;
                    }

                    @Override
                    protected int calculateTimeForDeceleration(int dx) {
                        return super.calculateTimeForDeceleration(dx) * 2;
                    }
                };
                linearSmoothScroller.setTargetPosition(position);
                startSmoothScroll(linearSmoothScroller);
            }
        });
        layoutManager.setBind(false);
        listView.setHorizontalScrollBarEnabled(false);
        listView.setVerticalScrollBarEnabled(false);
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 0, 0, 0, 0));
        listView.setAdapter(listAdapter = new ShareAdapter(context));
        listView.setGlowColor(getThemedColor(Theme.key_dialogScrollGlow));
        listView.setOnItemClickListener((view, position) -> {
            Object object;
            if (listView.getAdapter() == searchAdapter) {
                object = searchAdapter.getItem(position);
            } else {
                int section = listAdapter.getSectionForPosition(position);
                int row = listAdapter.getPositionInSectionForPosition(position);
                if (row < 0 || section < 0) {
                    return;
                }
                object = listAdapter.getItem(section, row);

            }
            if (object != null) {
                ContactsController.Contact contact;
                String firstName;
                String lastName;
                if (object instanceof ContactsController.Contact) {
                    contact = (ContactsController.Contact) object;
                    if (contact.user != null) {
                        firstName = contact.user.first_name;
                        lastName = contact.user.last_name;
                    } else {
                        firstName = contact.first_name;
                        lastName = contact.last_name;
                    }
                } else {
                    TLRPC.User user = (TLRPC.User) object;
                    contact = new ContactsController.Contact();
                    firstName = contact.first_name = user.first_name;
                    lastName = contact.last_name = user.last_name;
                    contact.phones.add(user.phone);
                    contact.user = user;
                }

                PhonebookShareAlert phonebookShareAlert = new PhonebookShareAlert(parentAlert.baseFragment, contact, null, null, null, firstName, lastName, resourcesProvider);
                phonebookShareAlert.setDelegate((user, notify, scheduleDate) -> {
                    parentAlert.dismiss(true);
                    delegate.didSelectContact(user, notify, scheduleDate);
                });
                phonebookShareAlert.show();
            }
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                parentAlert.updateLayout(ChatAttachAlertContactsLayout.this, true, dy);
                updateEmptyViewPosition();
            }
        });

        FrameLayout.LayoutParams frameLayoutParams = new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, AndroidUtilities.getShadowHeight(), Gravity.TOP | Gravity.LEFT);
        frameLayoutParams.topMargin = AndroidUtilities.dp(58);
        shadow = new View(context);
        shadow.setBackgroundColor(getThemedColor(Theme.key_dialogShadowLine));
        shadow.setAlpha(0.0f);
        shadow.setTag(1);
        addView(shadow, frameLayoutParams);

        addView(frameLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 58, Gravity.LEFT | Gravity.TOP));

        NotificationCenter.getInstance(parentAlert.currentAccount).addObserver(this, NotificationCenter.contactsDidLoad);
        updateEmptyView();
    }

    @Override
    void scrollToTop() {
        listView.smoothScrollToPosition(0);
    }

    @Override
    int getCurrentItemTop() {
        if (listView.getChildCount() <= 0) {
            return Integer.MAX_VALUE;
        }
        View child = listView.getChildAt(0);
        RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findContainingViewHolder(child);
        int top = child.getTop() - AndroidUtilities.dp(8);
        int newOffset = top > 0 && holder != null && holder.getAdapterPosition() == 0 ? top : 0;
        if (top >= 0 && holder != null && holder.getAdapterPosition() == 0) {
            newOffset = top;
            runShadowAnimation(false);
        } else {
            runShadowAnimation(true);
        }
        frameLayout.setTranslationY(newOffset);
        return newOffset + AndroidUtilities.dp(12);
    }

    @Override
    int getFirstOffset() {
        return getListTopPadding() + AndroidUtilities.dp(4);
    }

    @Override
    public void setTranslationY(float translationY) {
        super.setTranslationY(translationY);
        parentAlert.getSheetContainer().invalidate();
    }

    @Override
    int getListTopPadding() {
        return listView.getPaddingTop();
    }

    @Override
    void onPreMeasure(int availableWidth, int availableHeight) {
        int padding;
        if (parentAlert.sizeNotifierFrameLayout.measureKeyboardHeight() > AndroidUtilities.dp(20)) {
            padding = AndroidUtilities.dp(8);
            parentAlert.setAllowNestedScroll(false);
        } else {
            if (!AndroidUtilities.isTablet() && AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
                padding = (int) (availableHeight / 3.5f);
            } else {
                padding = (availableHeight / 5 * 2);
            }
            parentAlert.setAllowNestedScroll(true);
        }
        if (listView.getPaddingTop() != padding) {
            ignoreLayout = true;
            listView.setPadding(0, padding, 0, 0);
            ignoreLayout = false;
        }
    }

    @Override
    public void requestLayout() {
        if (ignoreLayout) {
            return;
        }
        super.requestLayout();
    }

    private void runShadowAnimation(final boolean show) {
        if (show && shadow.getTag() != null || !show && shadow.getTag() == null) {
            shadow.setTag(show ? null : 1);
            if (show) {
                shadow.setVisibility(View.VISIBLE);
            }
            if (shadowAnimation != null) {
                shadowAnimation.cancel();
            }
            shadowAnimation = new AnimatorSet();
            shadowAnimation.playTogether(ObjectAnimator.ofFloat(shadow, View.ALPHA, show ? 1.0f : 0.0f));
            shadowAnimation.setDuration(150);
            shadowAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (shadowAnimation != null && shadowAnimation.equals(animation)) {
                        if (!show) {
                            shadow.setVisibility(View.INVISIBLE);
                        }
                        shadowAnimation = null;
                    }
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    if (shadowAnimation != null && shadowAnimation.equals(animation)) {
                        shadowAnimation = null;
                    }
                }
            });
            shadowAnimation.start();
        }
    }

    private int getCurrentTop() {
        if (listView.getChildCount() != 0) {
            View child = listView.getChildAt(0);
            RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findContainingViewHolder(child);
            if (holder != null) {
                return listView.getPaddingTop() - (holder.getAdapterPosition() == 0 && child.getTop() >= 0 ? child.getTop() : 0);
            }
        }
        return -1000;
    }

    public void setDelegate(PhonebookShareAlertDelegate phonebookShareAlertDelegate) {
        delegate = phonebookShareAlertDelegate;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.contactsDidLoad) {
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        }
    }

    @Override
    void onDestroy() {
        NotificationCenter.getInstance(parentAlert.currentAccount).removeObserver(this, NotificationCenter.contactsDidLoad);
    }

    @Override
    void onShow(ChatAttachAlert.AttachAlertLayout previousLayout) {
        layoutManager.scrollToPositionWithOffset(0, 0);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateEmptyViewPosition();
    }

    private void updateEmptyViewPosition() {
        if (emptyView.getVisibility() != VISIBLE) {
            return;
        }
        View child = listView.getChildAt(0);
        if (child == null) {
            return;
        }
        emptyView.setTranslationY((emptyView.getMeasuredHeight() - getMeasuredHeight() + child.getTop()) / 2);
    }

    private void updateEmptyView() {
        boolean visible = listView.getAdapter().getItemCount() == 2;
        emptyView.setVisibility(visible ? VISIBLE : GONE);
        updateEmptyViewPosition();
    }

    public class ShareAdapter extends RecyclerListView.SectionsAdapter {

        private int currentAccount = UserConfig.selectedAccount;
        private Context mContext;

        public ShareAdapter(Context context) {
            mContext = context;
        }

        public Object getItem(int section, int position) {
            if (section == 0) {
                return null;
            }
            section--;
            HashMap<String, ArrayList<Object>> usersSectionsDict = ContactsController.getInstance(currentAccount).phoneBookSectionsDict;
            ArrayList<String> sortedUsersSectionsArray = ContactsController.getInstance(currentAccount).phoneBookSectionsArray;
            if (section < sortedUsersSectionsArray.size()) {
                ArrayList<Object> arr = usersSectionsDict.get(sortedUsersSectionsArray.get(section));
                if (position < arr.size()) {
                    return arr.get(position);
                }
            }
            return null;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder, int section, int row) {
            if (section == 0 || section == getSectionCount() - 1) {
                return false;
            }
            section--;
            HashMap<String, ArrayList<Object>> usersSectionsDict = ContactsController.getInstance(currentAccount).phoneBookSectionsDict;
            ArrayList<String> sortedUsersSectionsArray = ContactsController.getInstance(currentAccount).phoneBookSectionsArray;
            return row < usersSectionsDict.get(sortedUsersSectionsArray.get(section)).size();
        }

        @Override
        public int getSectionCount() {
            ArrayList<String> sortedUsersSectionsArray = ContactsController.getInstance(currentAccount).phoneBookSectionsArray;
            return sortedUsersSectionsArray.size() + 2;
        }

        @Override
        public int getCountForSection(int section) {
            if (section == 0 || section == getSectionCount() - 1) {
                return 1;
            }
            section--;
            HashMap<String, ArrayList<Object>> usersSectionsDict = ContactsController.getInstance(currentAccount).phoneBookSectionsDict;
            ArrayList<String> sortedUsersSectionsArray = ContactsController.getInstance(currentAccount).phoneBookSectionsArray;
            if (section < sortedUsersSectionsArray.size()) {
                return usersSectionsDict.get(sortedUsersSectionsArray.get(section)).size();
            }
            return 0;
        }

        @Override
        public View getSectionHeaderView(int section, View view) {
            return null;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0: {
                    view = new UserCell(mContext, resourcesProvider);
                    break;
                }
                case 1: {
                    view = new View(mContext);
                    view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(56)));
                    break;
                }
                case 2:
                default: {
                    view = new View(mContext);
                    break;
                }
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(int section, int position, RecyclerView.ViewHolder holder) {
            if (holder.getItemViewType() == 0) {
                UserCell userCell = (UserCell) holder.itemView;
                Object object = getItem(section, position);
                TLRPC.User user = null;
                boolean divider = section != getSectionCount() - 2 || position != getCountForSection(section) - 1;
                if (object instanceof ContactsController.Contact) {
                    ContactsController.Contact contact = (ContactsController.Contact) object;
                    if (contact.user != null) {
                        user = contact.user;
                    } else {
                        userCell.setCurrentId(contact.contact_id);
                        userCell.setData(null, ContactsController.formatName(contact.first_name, contact.last_name), () -> contact.phones.isEmpty() ? "" : PhoneFormat.getInstance().format(contact.phones.get(0)), divider);
                    }
                } else {
                    user = (TLRPC.User) object;
                }
                if (user != null) {
                    final TLRPC.User finalUser = user;
                    userCell.setData(user, null, () -> PhoneFormat.getInstance().format("+" + finalUser.phone), divider);
                }
            }
        }

        @Override
        public int getItemViewType(int section, int position) {
            if (section == 0) {
                return 1;
            } else if (section == getSectionCount() - 1) {
                return 2;
            }
            return 0;
        }

        @Override
        public String getLetter(int position) {
            return null;
        }

        @Override
        public void getPositionForScrollProgress(RecyclerListView listView, float progress, int[] position) {
            position[0] = 0;
            position[1] = 0;
        }

        @Override
        public void notifyDataSetChanged() {
            super.notifyDataSetChanged();
            updateEmptyView();
        }
    }

    public class ShareSearchAdapter extends RecyclerListView.SelectionAdapter {

        private Context mContext;
        private ArrayList<Object> searchResult = new ArrayList<>();
        private ArrayList<CharSequence> searchResultNames = new ArrayList<>();
        private Runnable searchRunnable;
        private int lastSearchId;

        public ShareSearchAdapter(Context context) {
            mContext = context;
        }

        public void search(final String query) {
            if (searchRunnable != null) {
                Utilities.searchQueue.cancelRunnable(searchRunnable);
                searchRunnable = null;
            }
            if (query == null) {
                searchResult.clear();
                searchResultNames.clear();
                notifyDataSetChanged();
            } else {
                int searchId = ++lastSearchId;
                Utilities.searchQueue.postRunnable(searchRunnable = () -> processSearch(query, searchId), 300);
            }
        }

        private void processSearch(final String query, final int searchId) {
            AndroidUtilities.runOnUIThread(() -> {
                final int currentAccount = UserConfig.selectedAccount;
                final ArrayList<ContactsController.Contact> contactsCopy = new ArrayList<>(ContactsController.getInstance(currentAccount).contactsBook.values());
                final ArrayList<TLRPC.TL_contact> contactsCopy2 = new ArrayList<>(ContactsController.getInstance(currentAccount).contacts);
                Utilities.searchQueue.postRunnable(() -> {
                    String search1 = query.trim().toLowerCase();
                    if (search1.length() == 0) {
                        lastSearchId = -1;
                        updateSearchResults(query, new ArrayList<>(), new ArrayList<>(), lastSearchId);
                        return;
                    }
                    String search2 = LocaleController.getInstance().getTranslitString(search1);
                    if (search1.equals(search2) || search2.length() == 0) {
                        search2 = null;
                    }
                    String[] search = new String[1 + (search2 != null ? 1 : 0)];
                    search[0] = search1;
                    if (search2 != null) {
                        search[1] = search2;
                    }

                    ArrayList<Object> resultArray = new ArrayList<>();
                    ArrayList<CharSequence> resultArrayNames = new ArrayList<>();
                    LongSparseIntArray foundUids = new LongSparseIntArray();

                    for (int a = 0; a < contactsCopy.size(); a++) {
                        ContactsController.Contact contact = contactsCopy.get(a);
                        String name = ContactsController.formatName(contact.first_name, contact.last_name).toLowerCase();
                        String tName = LocaleController.getInstance().getTranslitString(name);
                        String name2;
                        String tName2;
                        if (contact.user != null) {
                            name2 = ContactsController.formatName(contact.user.first_name, contact.user.last_name).toLowerCase();
                            tName2 = LocaleController.getInstance().getTranslitString(name);
                        } else {
                            name2 = null;
                            tName2 = null;
                        }
                        if (name.equals(tName)) {
                            tName = null;
                        }

                        int found = 0;
                        String username;
                        for (String q : search) {
                            if (name2 != null && (name2.startsWith(q) || name2.contains(" " + q)) || tName2 != null && (tName2.startsWith(q) || tName2.contains(" " + q))) {
                                found = 1;
                            } else if (contact.user != null && (username = UserObject.getPublicUsername(contact.user)) != null && username.startsWith(q)) {
                                found = 2;
                            } else if (name.startsWith(q) || name.contains(" " + q) || tName != null && (tName.startsWith(q) || tName.contains(" " + q))) {
                                found = 3;
                            }
                            if (found != 0 && (!contact.phones.isEmpty() || !contact.shortPhones.isEmpty())) {
                                if (found == 3) {
                                    resultArrayNames.add(AndroidUtilities.generateSearchName(contact.first_name, contact.last_name, q));
                                } else if (found == 1) {
                                    resultArrayNames.add(AndroidUtilities.generateSearchName(contact.user.first_name, contact.user.last_name, q));
                                } else {
                                    resultArrayNames.add(AndroidUtilities.generateSearchName("@" + UserObject.getPublicUsername(contact.user), null, "@" + q));
                                }
                                if (contact.user != null) {
                                    foundUids.put(contact.user.id, 1);
                                }
                                resultArray.add(contact);
                                break;
                            }
                        }
                    }

                    for (int a = 0; a < contactsCopy2.size(); a++) {
                        TLRPC.TL_contact contact = contactsCopy2.get(a);
                        if (foundUids.indexOfKey(contact.user_id) >= 0) {
                            continue;
                        }
                        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(contact.user_id);
                        String name = ContactsController.formatName(user.first_name, user.last_name).toLowerCase();
                        String tName = LocaleController.getInstance().getTranslitString(name);
                        if (name.equals(tName)) {
                            tName = null;
                        }

                        int found = 0;
                        String username;
                        for (String q : search) {
                            if (name.startsWith(q) || name.contains(" " + q) || tName != null && (tName.startsWith(q) || tName.contains(" " + q))) {
                                found = 1;
                            } else if ((username = UserObject.getPublicUsername(user)) != null && username.startsWith(q)) {
                                found = 2;
                            }

                            if (found != 0 && user.phone != null) {
                                if (found == 1) {
                                    resultArrayNames.add(AndroidUtilities.generateSearchName(user.first_name, user.last_name, q));
                                } else {
                                    resultArrayNames.add(AndroidUtilities.generateSearchName("@" + UserObject.getPublicUsername(user), null, "@" + q));
                                }
                                resultArray.add(user);
                                break;
                            }
                        }
                    }

                    updateSearchResults(query, resultArray, resultArrayNames, searchId);
                });
            });
        }

        private void updateSearchResults(final String query, final ArrayList<Object> users, final ArrayList<CharSequence> names, final int searchId) {
            AndroidUtilities.runOnUIThread(() -> {
                if (searchId != lastSearchId) {
                    return;
                }
                if (searchId != -1 && listView.getAdapter() != searchAdapter) {
                    listView.setAdapter(searchAdapter);
                }
                searchResult = users;
                searchResultNames = names;
                notifyDataSetChanged();
            });
        }

        @Override
        public int getItemCount() {
            return searchResult.size() + 2;
        }

        public Object getItem(int position) {
            position--;
            if (position < 0 || position >= searchResult.size()) {
                return null;
            }
            return searchResult.get(position);
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new UserCell(mContext, resourcesProvider);
                    break;
                case 1:
                    view = new View(mContext);
                    view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(56)));
                    break;
                case 2:
                default:
                    view = new View(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == 0) {
                UserCell userCell = (UserCell) holder.itemView;

                boolean divider = position != getItemCount() - 2;
                Object object = getItem(position);
                TLRPC.User user = null;
                if (object instanceof ContactsController.Contact) {
                    ContactsController.Contact contact = (ContactsController.Contact) object;
                    if (contact.user != null) {
                        user = contact.user;
                    } else {
                        userCell.setCurrentId(contact.contact_id);
                        userCell.setData(null, searchResultNames.get(position - 1), () -> contact.phones.isEmpty() ? "" : PhoneFormat.getInstance().format(contact.phones.get(0)), divider);
                    }
                } else {
                    user = (TLRPC.User) object;
                }
                if (user != null) {
                    final TLRPC.User finalUser = user;
                    userCell.setData(user, searchResultNames.get(position - 1), () -> PhoneFormat.getInstance().format("+" + finalUser.phone), divider);
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == 0;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return 1;
            } else if (position == getItemCount() - 1) {
                return 2;
            }
            return 0;
        }

        @Override
        public void notifyDataSetChanged() {
            super.notifyDataSetChanged();
            updateEmptyView();
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {

        ThemeDescription.ThemeDescriptionDelegate cellDelegate = () -> {
            if (listView != null) {
                int count = listView.getChildCount();
                for (int a = 0; a < count; a++) {
                    View child = listView.getChildAt(a);
                    if (child instanceof UserCell) {
                        ((UserCell) child).update(0);
                    }
                }
            }
        };

        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        themeDescriptions.add(new ThemeDescription(frameLayout, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_dialogBackground));

        themeDescriptions.add(new ThemeDescription(shadow, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_dialogShadowLine));

        themeDescriptions.add(new ThemeDescription(searchField.getSearchBackground(), ThemeDescription.FLAG_BACKGROUNDFILTER, null, null, null, null, Theme.key_dialogSearchBackground));
        themeDescriptions.add(new ThemeDescription(searchField, ThemeDescription.FLAG_IMAGECOLOR, new Class[]{SearchField.class}, new String[]{"searchIconImageView"}, null, null, null, Theme.key_dialogSearchIcon));
        themeDescriptions.add(new ThemeDescription(searchField, ThemeDescription.FLAG_IMAGECOLOR, new Class[]{SearchField.class}, new String[]{"clearSearchImageView"}, null, null, null, Theme.key_dialogSearchIcon));
        themeDescriptions.add(new ThemeDescription(searchField.getSearchEditText(), ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_dialogSearchText));
        themeDescriptions.add(new ThemeDescription(searchField.getSearchEditText(), ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_dialogSearchHint));
        themeDescriptions.add(new ThemeDescription(searchField.getSearchEditText(), ThemeDescription.FLAG_CURSORCOLOR, null, null, null, null, Theme.key_featuredStickers_addedIcon));

        themeDescriptions.add(new ThemeDescription(emptyView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_emptyListPlaceholder));
        themeDescriptions.add(new ThemeDescription(emptyView, ThemeDescription.FLAG_PROGRESSBAR, null, null, null, null, Theme.key_progressCircle));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_dialogScrollGlow));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{UserCell.class}, new String[]{"nameTextView"}, null, null, null, Theme.key_dialogTextGray2));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{UserCell.class}, new String[]{"statusTextView"}, null, null, cellDelegate, Theme.key_dialogTextGray2));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{UserCell.class}, null, Theme.avatarDrawables, null, Theme.key_avatar_text));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_avatar_backgroundRed));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_avatar_backgroundOrange));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_avatar_backgroundViolet));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_avatar_backgroundGreen));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_avatar_backgroundCyan));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_avatar_backgroundBlue));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_avatar_backgroundPink));

        return themeDescriptions;
    }
}
