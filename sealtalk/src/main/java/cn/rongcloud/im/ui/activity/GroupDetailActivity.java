package cn.rongcloud.im.ui.activity;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

import cn.rongcloud.im.R;
import cn.rongcloud.im.common.Constant;
import cn.rongcloud.im.common.IntentExtra;
import cn.rongcloud.im.db.model.GroupEntity;
import cn.rongcloud.im.im.IMManager;
import cn.rongcloud.im.model.AddMemberResult;
import cn.rongcloud.im.model.GroupMember;
import cn.rongcloud.im.model.GroupNoticeResult;
import cn.rongcloud.im.model.RegularClearStatusResult;
import cn.rongcloud.im.model.Resource;
import cn.rongcloud.im.model.ScreenCaptureResult;
import cn.rongcloud.im.model.Status;
import cn.rongcloud.im.model.qrcode.QrCodeDisplayType;
import cn.rongcloud.im.ui.adapter.GridGroupMemberAdapter;
import cn.rongcloud.im.ui.dialog.CommonDialog;
import cn.rongcloud.im.ui.dialog.GroupNoticeDialog;
import cn.rongcloud.im.ui.dialog.LoadingDialog;
import cn.rongcloud.im.ui.dialog.SelectCleanTimeDialog;
import cn.rongcloud.im.ui.dialog.SelectPictureBottomDialog;
import cn.rongcloud.im.ui.dialog.SimpleInputDialog;
import cn.rongcloud.im.ui.view.SealTitleBar;
import cn.rongcloud.im.ui.view.SettingItemView;
import cn.rongcloud.im.ui.view.UserInfoItemView;
import cn.rongcloud.im.ui.widget.WrapHeightGridView;
import cn.rongcloud.im.utils.CheckPermissionUtils;
import cn.rongcloud.im.utils.ImageLoaderUtils;
import cn.rongcloud.im.utils.ToastUtils;
import cn.rongcloud.im.viewmodel.GroupDetailViewModel;
import cn.rongcloud.im.utils.log.SLog;
import io.rong.imkit.conversation.ConversationSettingViewModel;
import io.rong.imkit.conversation.extension.component.emoticon.AndroidEmoji;
import io.rong.imkit.model.OperationResult;
import io.rong.imkit.userinfo.RongUserInfoManager;
import io.rong.imkit.widget.dialog.PromptPopupDialog;
import io.rong.imlib.model.Conversation;
import io.rong.imlib.model.Group;

/**
 * ??????????????????
 */
public class GroupDetailActivity extends TitleBaseActivity implements View.OnClickListener {
    private final String TAG = "GroupDetailActivity";
    /**
     * ????????????????????????????????????
     */
    private final int REQUEST_ADD_GROUP_MEMBER = 1000;
    /**
     * ????????????????????????????????????
     */
    private final int REQUEST_REMOVE_GROUP_MEMBER = 1001;

    /**
     * ?????????????????????
     */
    private final int SHOW_GROUP_MEMBER_LIMIT = 30;

    private SealTitleBar titleBar;
    private WrapHeightGridView groupMemberGv;

    private Button quitGroupBtn;
    private LoadingDialog loadingDialog;

    private String groupId;
    private Conversation.ConversationType conversationType;
    private GroupDetailViewModel groupDetailViewModel;
    private ConversationSettingViewModel conversationSettingViewModel;
    private GridGroupMemberAdapter memberAdapter;
    private String groupName;
    private String grouportraitUrl;
    private SettingItemView groupPortraitUiv;
    private SettingItemView allGroupMemberSiv;
    private SettingItemView groupNameSiv;
    private SettingItemView notifyNoticeSiv;
    private SettingItemView onTopSiv;
    private SettingItemView isToContactSiv;
    private SettingItemView groupManagerSiv;
    private SettingItemView groupNoticeSiv;
    private SettingItemView groupUserInfoSiv;
    private SettingItemView cleanTimingSiv;
    private SettingItemView screenShotSiv;
    private SettingItemView groupQRCodeSiv;
    private TextView screenShotTip;

    private boolean isScreenShotSivClicked;
    private String lastGroupNoticeContent;
    private long lastGroupNoticeTime;
    private String groupCreatorId;

    private final int REQUEST_CODE_PERMISSION = 115;
    private String[] permissions = {Manifest.permission.READ_EXTERNAL_STORAGE};


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        titleBar = getTitleBar();
        titleBar.setTitle(R.string.profile_group_info);

        setContentView(R.layout.profile_activity_group_detail);

        Intent intent = getIntent();
        if (intent == null) {
            SLog.e(TAG, "intent is null, finish " + TAG);
            return;
        }

        conversationType = (Conversation.ConversationType) intent.getSerializableExtra(IntentExtra.SERIA_CONVERSATION_TYPE);
        groupId = intent.getStringExtra(IntentExtra.STR_TARGET_ID);
        if (groupId == null || conversationType == null) {
            SLog.e(TAG, "targetId or conversationType is null, finish" + TAG);
            return;
        }

        initView();
        initViewModel();
    }

    // ???????????????
    private void initView() {
        // ??????????????????
        groupMemberGv = findViewById(R.id.profile_gv_group_member);
        memberAdapter = new GridGroupMemberAdapter(this, SHOW_GROUP_MEMBER_LIMIT);
        memberAdapter.setAllowAddMember(true);
        groupMemberGv.setAdapter(memberAdapter);
        memberAdapter.setOnItemClickedListener(new GridGroupMemberAdapter.OnItemClickedListener() {
            @Override
            public void onAddOrDeleteMemberClicked(boolean isAdd) {
                toMemberManage(isAdd);
            }

            @Override
            public void onMemberClicked(GroupMember groupMember) {
                showMemberInfo(groupMember);
            }
        });

        // ???????????????
        allGroupMemberSiv = findViewById(R.id.profile_siv_all_group_member);
        allGroupMemberSiv.setOnClickListener(this);

        // ??????????????????
        findViewById(R.id.profile_siv_group_search_history_message).setOnClickListener(this);
        // ?????????
        groupPortraitUiv = findViewById(R.id.profile_uiv_group_portrait_container);
        groupPortraitUiv.setOnClickListener(this);
        // ?????????
        groupNameSiv = findViewById(R.id.profile_siv_group_name_container);
        groupNameSiv.setOnClickListener(this);
        // ????????????
        groupQRCodeSiv = findViewById(R.id.profile_siv_group_qrcode);
        groupQRCodeSiv.setSelected(true);
        groupQRCodeSiv.setOnClickListener(this);
        // ?????????
        groupNoticeSiv = findViewById(R.id.profile_siv_group_notice);
        groupNoticeSiv.setOnClickListener(this);
        //??????????????????
        groupUserInfoSiv = findViewById(R.id.profile_siv_group_user_info);
        groupUserInfoSiv.setOnClickListener(this);

        groupManagerSiv = findViewById(R.id.profile_siv_group_manager);


        // ???????????????
        notifyNoticeSiv = findViewById(R.id.profile_siv_message_notice);
        notifyNoticeSiv.setSwitchCheckListener((buttonView, isChecked) ->
                conversationSettingViewModel.setNotificationStatus(isChecked ? Conversation.ConversationNotificationStatus.DO_NOT_DISTURB : Conversation.ConversationNotificationStatus.NOTIFY));

        // ????????????
        onTopSiv = findViewById(R.id.profile_siv_group_on_top);
        onTopSiv.setSwitchCheckListener((buttonView, isChecked) ->
                conversationSettingViewModel.setConversationTop(isChecked, false));

        // ??????????????????
        isToContactSiv = findViewById(R.id.profile_siv_group_save_to_contact);
        isToContactSiv.setSwitchCheckListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    groupDetailViewModel.saveToContact();
                } else {
                    groupDetailViewModel.removeFromContact();
                }
            }
        });
        //????????????
        screenShotTip = findViewById(R.id.tv_screen_shot_tip);
        screenShotSiv = findViewById(R.id.profile_siv_group_screen_shot_notification);
        screenShotSiv.setSwitchTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!isScreenShotSivClicked) {
                    isScreenShotSivClicked = true;
                }
                return false;
            }
        });
        screenShotSiv.setSwitchCheckListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                //????????????????????????
                if (!isScreenShotSivClicked) {
                    return;
                }
                // 0 ?????? 1 ??????
                if (isChecked) {
                    //???????????????????????????
                    if (!requestReadPermissions()) {
                        return;
                    }
                    groupDetailViewModel.setScreenCaptureStatus(1);
                } else {
                    groupDetailViewModel.setScreenCaptureStatus(0);
                }
            }
        });

        // ????????????
        findViewById(R.id.profile_siv_group_clean_message).setOnClickListener(this);

        // ????????????
        quitGroupBtn = findViewById(R.id.profile_btn_group_quit);
        quitGroupBtn.setOnClickListener(this);


        groupManagerSiv.setOnClickListener(this);

        //?????????????????????
        cleanTimingSiv = findViewById(R.id.profile_siv_group_clean_timming);
        cleanTimingSiv.setOnClickListener(this);
    }

    private boolean requestReadPermissions() {
        return CheckPermissionUtils.requestPermissions(this, permissions, REQUEST_CODE_PERMISSION);
    }

    private void initViewModel() {
        groupDetailViewModel = ViewModelProviders.of(this,
                new GroupDetailViewModel.Factory(this.getApplication(), groupId, conversationType))
                .get(GroupDetailViewModel.class);

        // ?????????????????????
        groupDetailViewModel.getMyselfInfo().observe(this, new Observer<GroupMember>() {
            @Override
            public void onChanged(GroupMember member) {
                // ????????????????????????????????????
                if (member.getMemberRole() == GroupMember.Role.GROUP_OWNER) {
                    quitGroupBtn.setText(R.string.profile_dismiss_group);
                    // ?????????????????????????????????????????????????????????
                    memberAdapter.setAllowDeleteMember(true);
                    groupManagerSiv.setVisibility(View.VISIBLE);
                    //????????????????????????????????????
                    screenShotSiv.setVisibility(View.VISIBLE);
                    screenShotTip.setVisibility(View.VISIBLE);
                } else if (member.getMemberRole() == GroupMember.Role.MANAGEMENT) {
                    groupPortraitUiv.setClickable(false);
                    groupNameSiv.setClickable(false);
                    quitGroupBtn.setText(R.string.profile_quit_group);
                    memberAdapter.setAllowDeleteMember(true);
                    groupManagerSiv.setVisibility(View.GONE);
                    screenShotSiv.setVisibility(View.VISIBLE);
                    screenShotTip.setVisibility(View.VISIBLE);
                } else {
                    groupPortraitUiv.setClickable(false);
                    groupNameSiv.setClickable(false);
                    quitGroupBtn.setText(R.string.profile_quit_group);
                    memberAdapter.setAllowDeleteMember(false);
                    groupManagerSiv.setVisibility(View.GONE);
                    screenShotSiv.setVisibility(View.GONE);
                    screenShotTip.setVisibility(View.GONE);
                }
            }
        });

        // ??????????????????
        groupDetailViewModel.getGroupInfo().observe(this, resource -> {
            if (resource.data != null) {
                updateGroupInfoView(resource.data);
            }

            if (resource.status == Status.ERROR) {
                ToastUtils.showErrorToast(resource.code);
            }

            if (resource.status == Status.SUCCESS && resource.data == null) {
                backToMain();
            }
        });

        // ????????????????????????
        groupDetailViewModel.getGroupMemberList().observe(this, resource -> {
            if (resource.data != null && resource.data.size() > 0) {
                updateGroupMemberList(resource.data);
            }

            if (resource.status == Status.ERROR) {
                ToastUtils.showErrorToast(resource.code);
            }

            if (resource.status == Status.SUCCESS && resource.data == null) {
                backToMain();
            }
        });

        conversationSettingViewModel = ViewModelProviders.of(this,
                new ConversationSettingViewModel.Factory(this.getApplication(), conversationType, groupId))
                .get(ConversationSettingViewModel.class);

        conversationSettingViewModel.getNotificationStatus().observe(this, new Observer<Conversation.ConversationNotificationStatus>() {
            @Override
            public void onChanged(Conversation.ConversationNotificationStatus conversationNotificationStatus) {
                if (conversationNotificationStatus.equals(Conversation.ConversationNotificationStatus.DO_NOT_DISTURB)) {
                    notifyNoticeSiv.setChecked(true);
                } else {
                    notifyNoticeSiv.setChecked(false);
                }
            }
        });

        conversationSettingViewModel.getTopStatus().observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean isTop) {
                onTopSiv.setChecked(isTop);
            }
        });

        conversationSettingViewModel.getOperationResult().observe(this, new Observer<OperationResult>() {
            @Override
            public void onChanged(OperationResult operationResult) {
                if (operationResult.mResultCode != 0) {
                    if (operationResult.mAction.equals(OperationResult.Action.CLEAR_CONVERSATION_MESSAGES)) {
                        ToastUtils.showToast(R.string.common_clear_failure);
                    } else {
                        ToastUtils.showToast(R.string.common_set_failed);
                    }
                } else if (operationResult.mAction.equals(OperationResult.Action.CLEAR_CONVERSATION_MESSAGES)) {
                    ToastUtils.showToast(R.string.common_clear_success);
                }
            }
        });

        // ???????????????????????????
        groupDetailViewModel.getUploadPortraitResult().observe(this, resource -> {
            if (resource.status == Status.LOADING) {
                if (loadingDialog != null) {
                    loadingDialog = new LoadingDialog();
                    loadingDialog.show(getSupportFragmentManager(), null);
                }
            } else {
                if (loadingDialog != null) {
                    loadingDialog.dismiss();
                    loadingDialog = null;
                }
            }

            if (resource.status == Status.ERROR) {
                ToastUtils.showToast(R.string.profile_upload_portrait_failed);
            }
        });

        // ????????????????????????
        groupDetailViewModel.getAddGroupMemberResult().observe(this, new Observer<Resource<List<AddMemberResult>>>() {
            @Override
            public void onChanged(Resource<List<AddMemberResult>> resource) {
                if (resource.status == Status.ERROR) {
                    ToastUtils.showToast(resource.message);
                } else if (resource.status == Status.SUCCESS) {
                    if (resource.data != null && resource.data.size() > 0) {
                        String tips = getString(R.string.seal_add_success);
                        //1 ????????????, 2 ????????????????????????, 3 ???????????????????????????
                        for (AddMemberResult result : resource.data) {
                            if (result.status == 3) {
                                tips = getString(R.string.seal_add_need_member_agree);
                            } else if (result.status == 2) {
                                if (!tips.equals(getString(R.string.seal_add_need_member_agree))) {
                                    tips = getString(R.string.seal_add_need_manager_agree);
                                }
                            }
                        }
                        ToastUtils.showToast(tips);
                    }
                }
            }
        });

        // ????????????????????????
        groupDetailViewModel.getRemoveGroupMemberResult().observe(this, new Observer<Resource<Void>>() {
            @Override
            public void onChanged(Resource<Void> resource) {
                if (resource.status == Status.ERROR) {
                    ToastUtils.showToast(resource.message);
                }
            }
        });

        // ????????????????????????
        groupDetailViewModel.getRenameGroupResult().observe(this, new Observer<Resource<Void>>() {
            @Override
            public void onChanged(Resource<Void> resource) {
                if (resource.status == Status.ERROR) {
                    ToastUtils.showToast(resource.message);
                } else if (resource.status == Status.SUCCESS) {
                    // ??????????????????????????????????????????????????????????????????????????????????????????
                    groupDetailViewModel.refreshGroupInfo();
                }
            }
        });

        // ??????????????????
        groupDetailViewModel.getExitGroupResult().observe(this, new Observer<Resource<Void>>() {
            @Override
            public void onChanged(Resource<Void> resource) {
                if (resource.status == Status.SUCCESS) {
                    backToMain();
                } else if (resource.status == Status.ERROR) {
                    ToastUtils.showToast(resource.message);
                }
            }
        });

        // ????????????????????????
        groupDetailViewModel.getSaveToContact().observe(this, new Observer<Resource<Void>>() {
            @Override
            public void onChanged(Resource<Void> resource) {
                if (resource.status == Status.SUCCESS) {
                    ToastUtils.showToast(R.string.common_add_successful);
                } else if (resource.status == Status.ERROR) {
                    ToastUtils.showErrorToast(resource.code);
                }
            }
        });

        // ??????????????????????????????
        groupDetailViewModel.getRemoveFromContactResult().observe(this, new Observer<Resource<Void>>() {
            @Override
            public void onChanged(Resource<Void> resource) {
                if (resource.status == Status.SUCCESS) {
                    ToastUtils.showToast(R.string.common_remove_successful);
                } else if (resource.status == Status.ERROR) {
                    ToastUtils.showErrorToast(resource.code);
                }
            }
        });

        // ???????????????
        groupDetailViewModel.getGroupNoticeResult().observe(this, new Observer<Resource<GroupNoticeResult>>() {
            @Override
            public void onChanged(Resource<GroupNoticeResult> resource) {
                if (resource.status == Status.SUCCESS) {
                    GroupNoticeResult lastGroupNotice = resource.data;
                    if (lastGroupNotice != null) {
                        lastGroupNoticeContent = lastGroupNotice.getContent();
                        lastGroupNoticeTime = lastGroupNotice.getTimestamp();
                    }
                }
            }
        });

        groupDetailViewModel.getRegularClearResult().observe(this, new Observer<Resource<Void>>() {
            @Override
            public void onChanged(Resource<Void> resultResource) {
                if (resultResource.status == Status.SUCCESS) {
                    ToastUtils.showToast(getString(R.string.seal_set_clean_time_success));
                    //groupDetailViewModel.requestRegularState(groupId);
                } else if (resultResource.status == Status.ERROR) {
                    ToastUtils.showToast(getString(R.string.seal_set_clean_time_fail));
                }
            }
        });

        groupDetailViewModel.getRegularState().observe(this, new Observer<Resource<Integer>>() {
            @Override
            public void onChanged(Resource<Integer> groupRegularClearResultResource) {
                if (groupRegularClearResultResource.status != Status.LOADING && groupRegularClearResultResource.data != null) {
                    updateCleanTimingSiv(groupRegularClearResultResource.data);
                } else if (groupRegularClearResultResource.status != Status.LOADING) {
                    cleanTimingSiv.setValue(getString(R.string.seal_set_clean_time_state_not));
                }
            }
        });

        // ????????????????????????
        groupDetailViewModel.getScreenCaptureStatusResult().observe(this, new Observer<Resource<ScreenCaptureResult>>() {
            @Override
            public void onChanged(Resource<ScreenCaptureResult> screenCaptureResultResource) {
                if (screenCaptureResultResource.status == Status.SUCCESS) {
                    //0 ?????? 1 ??????
                    if (screenCaptureResultResource.data != null && screenCaptureResultResource.data.status == 1) {
                        screenShotSiv.setCheckedImmediately(true);
                    }
                }
            }
        });

        // ??????????????????????????????
        groupDetailViewModel.getSetScreenCaptureResult().observe(this, new Observer<Resource<Void>>() {
            @Override
            public void onChanged(Resource<Void> voidResource) {
                if (voidResource.status == Status.SUCCESS) {
                    ToastUtils.showToast(getString(R.string.seal_set_clean_time_success));
                } else if (voidResource.status == Status.ERROR) {
                    ToastUtils.showToast(getString(R.string.seal_set_clean_time_fail));
                }
            }
        });
    }

    private void updateCleanTimingSiv(int state) {
        if (state == RegularClearStatusResult.ClearStatus.CLOSE.getValue()) {
            cleanTimingSiv.setValue(getString(R.string.seal_set_clean_time_state_not));
        } else if (state == RegularClearStatusResult.ClearStatus.THIRTY_SIX_HOUR.getValue()) {
            cleanTimingSiv.setValue(getString(R.string.seal_dialog_select_clean_time_36));
        } else if (state == RegularClearStatusResult.ClearStatus.THREE_DAYS.getValue()) {
            cleanTimingSiv.setValue(getString(R.string.seal_dialog_select_clean_time_3));
        } else if (state == RegularClearStatusResult.ClearStatus.SEVEN_DAYS.getValue()) {
            cleanTimingSiv.setValue(getString(R.string.seal_dialog_select_clean_time_7));
        }
    }


    /**
     * ???????????????
     *
     * @return
     */
    private boolean isGroupOwner() {
        if (groupDetailViewModel != null) {
            GroupMember selfGroupInfo = groupDetailViewModel.getMyselfInfo().getValue();
            if (selfGroupInfo != null) {
                return selfGroupInfo.getMemberRole() == GroupMember.Role.GROUP_OWNER;
            } else {
                return false;
            }
        }

        return false;
    }

    /**
     * ?????????????????????
     *
     * @return
     */
    private boolean isGroupManager() {
        if (groupDetailViewModel != null) {
            GroupMember selfGroupInfo = groupDetailViewModel.getMyselfInfo().getValue();
            if (selfGroupInfo != null) {
                return selfGroupInfo.getMemberRole() == GroupMember.Role.MANAGEMENT;
            }
        }
        return false;
    }

    /**
     * ???????????????
     *
     * @param groupInfo
     */
    private void updateGroupInfoView(GroupEntity groupInfo) {
        // ??????
        String title = getString(R.string.profile_group_info) + "(" + groupInfo.getMemberCount() + ")";
        titleBar.setTitle(title);

        // ???????????????
        String allMemberTxt = getString(R.string.profile_all_group_member) + "(" + groupInfo.getMemberCount() + ")";
        allGroupMemberSiv.setContent(allMemberTxt);
        // ??????????????????
        grouportraitUrl = groupInfo.getPortraitUri();
        if (groupPortraitUiv.getRightImageView() != null) {
            Glide.with(GroupDetailActivity.this)
                    .load(groupInfo.getPortraitUri())
                    .circleCrop()
                    .into(groupPortraitUiv.getRightImageView());
        }
        // ?????????
        groupName = groupInfo.getName();
        groupNameSiv.setValue(groupInfo.getName());

        // ?????????????????????
        int isInContact = groupInfo.getIsInContact();
        if (isInContact == 0) {
            isToContactSiv.setCheckedImmediately(false);
        } else {
            isToContactSiv.setCheckedImmediately(true);
        }

        groupCreatorId = groupInfo.getCreatorId();
    }

    /**
     * ?????????????????????
     *
     * @param groupMemberList
     */
    private void updateGroupMemberList(List<GroupMember> groupMemberList) {
        memberAdapter.updateListView(groupMemberList);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.profile_siv_all_group_member:
                showAllGroupMember();
                break;
            case R.id.profile_siv_group_search_history_message:
                showSearchHistoryMessage();
                break;
            case R.id.profile_uiv_group_portrait_container:
                setGroupPortrait();
                break;
            case R.id.profile_siv_group_name_container:
                editGroupName();
                break;
            case R.id.profile_siv_group_qrcode:
                showGroupQrCode();
                break;
            case R.id.profile_siv_group_notice:
                showGroupNotice();
                break;
            case R.id.profile_siv_group_user_info:
                Intent intentUserInfo = new Intent(this, GroupUserInfoActivity.class);
                intentUserInfo.putExtra(IntentExtra.GROUP_ID, groupId);
                intentUserInfo.putExtra(IntentExtra.STR_TARGET_ID, IMManager.getInstance().getCurrentId());
                startActivity(intentUserInfo);
                break;
            case R.id.profile_siv_group_clean_message:
                showCleanMessageDialog();
                break;
            case R.id.profile_btn_group_quit:
                quitOrDeleteGroup();
                break;
            case R.id.profile_siv_group_manager:
                Intent intent = new Intent(this, GroupManagerActivity.class);
                intent.putExtra(IntentExtra.GROUP_ID, groupId);
                startActivity(intent);
                break;
            case R.id.profile_siv_group_clean_timming:
                showRegualrClearDialog();
                break;
            default:
                break;
        }
    }

    /**
     * ??????????????????
     *
     * @param groupMember
     */
    private void showMemberInfo(GroupMember groupMember) {
        Intent intent = new Intent(this, UserDetailActivity.class);
        intent.putExtra(IntentExtra.STR_TARGET_ID, groupMember.getUserId());
        intent.putExtra(IntentExtra.GROUP_ID, groupMember.getGroupId());
        Group groupInfo = RongUserInfoManager.getInstance().getGroupInfo(groupId);
        if (groupInfo != null) {
            intent.putExtra(IntentExtra.STR_GROUP_NAME, groupInfo.getName());
        }
        startActivity(intent);
    }

    /**
     * ????????????????????????
     *
     * @param isAdd
     */
    private void toMemberManage(boolean isAdd) {
        if (isAdd) {
            Intent intent = new Intent(this, SelectFriendExcludeGroupActivity.class);
            intent.putExtra(IntentExtra.STR_TARGET_ID, groupId);
            startActivityForResult(intent, REQUEST_ADD_GROUP_MEMBER);
        } else {
            Intent intent = new Intent(this, SelectGroupMemberActivity.class);
            intent.putExtra(IntentExtra.STR_TARGET_ID, groupId);
            ArrayList<String> excludeList = new ArrayList<>();  // ????????????????????? id ??????
            String currentId = IMManager.getInstance().getCurrentId();
            excludeList.add(currentId);

            // ??????????????????????????????????????????????????????????????????????????????
            if (groupCreatorId != null && !currentId.equals(groupCreatorId)) {
                excludeList.add(groupCreatorId);
            }

            intent.putExtra(IntentExtra.LIST_EXCLUDE_ID_LIST, excludeList);
            intent.putExtra(IntentExtra.TITLE, "????????????");
            startActivityForResult(intent, REQUEST_REMOVE_GROUP_MEMBER);
        }
    }

    /**
     * ????????????????????????
     */
    public void showAllGroupMember() {
        Intent intent = new Intent(this, GroupMemberListActivity.class);
        intent.putExtra(IntentExtra.STR_TARGET_ID, groupId);
        startActivity(intent);
    }

    /**
     * ????????????????????????
     */
    public void showSearchHistoryMessage() {
        Intent intent = new Intent(this, SearchHistoryMessageActivity.class);
        intent.putExtra(IntentExtra.STR_TARGET_ID, groupId);
        intent.putExtra(IntentExtra.SERIA_CONVERSATION_TYPE, Conversation.ConversationType.GROUP);
        intent.putExtra(IntentExtra.STR_CHAT_NAME, groupName);
        intent.putExtra(IntentExtra.STR_CHAT_PORTRAIT, grouportraitUrl);
        startActivity(intent);
    }

    /**
     * ??????????????????
     */
    private void showGroupQrCode() {
        Intent intent = new Intent(this, QrCodeDisplayActivity.class);
        intent.putExtra(IntentExtra.STR_TARGET_ID, groupId);
        intent.putExtra(IntentExtra.START_FROM_ID, IMManager.getInstance().getCurrentId());
        intent.putExtra(IntentExtra.SERIA_QRCODE_DISPLAY_TYPE, QrCodeDisplayType.GROUP);
        startActivity(intent);
    }

    /**
     * ???????????????
     */
    private void editGroupName() {

        SimpleInputDialog dialog = new SimpleInputDialog();
        dialog.setInputHint(getString(R.string.profile_hint_new_group_name));
        dialog.setInputDialogListener(new SimpleInputDialog.InputDialogListener() {
            @Override
            public boolean onConfirmClicked(EditText input) {
                String name = input.getText().toString();

                if (name.length() < Constant.GROUP_NAME_MIN_LENGTH || name.length() > Constant.GROUP_NAME_MAX_LENGTH) {
                    ToastUtils.showToast(getString(R.string.profile_group_name_word_limit_format, Constant.GROUP_NAME_MIN_LENGTH, Constant.GROUP_NAME_MAX_LENGTH));
                    return true;
                }

                if (AndroidEmoji.isEmoji(name) && name.length() < Constant.GROUP_NAME_EMOJI_MIN_LENGTH) {
                    ToastUtils.showToast(getString(R.string.profile_group_name_emoji_too_short));
                    return true;
                }

                // ??????????????????
                groupDetailViewModel.renameGroupName(name);
                return true;
            }
        });
        dialog.show(getSupportFragmentManager(), null);
    }

    /**
     * ??????????????????
     */
    private void setGroupPortrait() {

        SelectPictureBottomDialog.Builder builder = new SelectPictureBottomDialog.Builder();
        builder.setOnSelectPictureListener(new SelectPictureBottomDialog.OnSelectPictureListener() {
            @Override
            public void onSelectPicture(Uri uri) {
                SLog.d(TAG, "select picture, uri:" + uri);
                groupDetailViewModel.setGroupPortrait(uri);
            }
        });
        SelectPictureBottomDialog dialog = builder.build();
        dialog.show(getSupportFragmentManager(), null);
    }

    /**
     * ???????????????
     */
    private void showGroupNotice() {
        // ?????????????????????????????????
        if (isGroupOwner() || isGroupManager()) {
            Intent intent = new Intent(this, GroupNoticeActivity.class);
            intent.putExtra(IntentExtra.STR_TARGET_ID, groupId);
            intent.putExtra(IntentExtra.SERIA_CONVERSATION_TYPE, Conversation.ConversationType.GROUP);
            startActivity(intent);
        } else {
            GroupNoticeDialog commonDialog = new GroupNoticeDialog();
            commonDialog.setNoticeContent(lastGroupNoticeContent);
            commonDialog.setNoticeUpdateTime(lastGroupNoticeTime);
            commonDialog.show(getSupportFragmentManager(), null);
        }
    }

    /**
     * ?????????????????????
     */
    private void quitOrDeleteGroup() {
        CommonDialog.Builder builder = new CommonDialog.Builder();
        // ???????????????????????????????????????
        if (isGroupOwner()) {
            builder.setContentMessage(getString(R.string.profile_confirm_dismiss_group));
        } else {
            builder.setContentMessage(getString(R.string.profile_confirm_quit_group));
        }
        builder.setDialogButtonClickListener(new CommonDialog.OnDialogButtonClickListener() {
            @Override
            public void onPositiveClick(View v, Bundle bundle) {
                // ??????????????????????????????????????????????????????
                if (isGroupOwner()) {
                    groupDetailViewModel.dismissGroup();
                } else {
                    groupDetailViewModel.exitGroup();
                }
            }

            @Override
            public void onNegativeClick(View v, Bundle bundle) {
            }
        });
        builder.build().show(getSupportFragmentManager(), null);
    }

    /**
     * ?????????????????????????????????
     */
    private void showCleanMessageDialog() {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.profile_clean_group_chat_history))
                .setPositiveButton(getString(R.string.rc_clear), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        conversationSettingViewModel.clearMessages(0, false);

                    }
                }).setNegativeButton(R.string.rc_cancel, null).show();
    }

    /**
     * ?????????????????????????????????
     */
    private void showRegualrClearDialog() {
        if (!isGroupOwner()) {
            ToastUtils.showToast(getString(R.string.seal_set_clean_time_not_owner_tip));
            return;
        }
        SelectCleanTimeDialog dialog = new SelectCleanTimeDialog();
        dialog.setOnDialogButtonClickListener(new SelectCleanTimeDialog.OnDialogButtonClickListener() {
            @Override
            public void onThirtySixHourClick() {
                setRegularClear(RegularClearStatusResult.ClearStatus.THIRTY_SIX_HOUR.getValue());
            }

            @Override
            public void onThreeDayClick() {
                setRegularClear(RegularClearStatusResult.ClearStatus.THREE_DAYS.getValue());
            }

            @Override
            public void onSevenDayClick() {
                setRegularClear(RegularClearStatusResult.ClearStatus.SEVEN_DAYS.getValue());
            }

            @Override
            public void onNotCleanClick() {
                setRegularClear(RegularClearStatusResult.ClearStatus.CLOSE.getValue());
            }
        });
        dialog.show(getSupportFragmentManager(), "regular_clear");
    }

    private void setRegularClear(int time) {
        groupDetailViewModel.setRegularClear(time);
    }

    private void backToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (data == null) return;

        if (requestCode == REQUEST_ADD_GROUP_MEMBER && resultCode == RESULT_OK) {
            // ??????????????????
            List<String> addMemberList = data.getStringArrayListExtra(IntentExtra.LIST_STR_ID_LIST);
            SLog.i(TAG, "addMemberList.size(): " + addMemberList.size());
            groupDetailViewModel.addGroupMember(addMemberList);
        } else if (requestCode == REQUEST_REMOVE_GROUP_MEMBER && resultCode == RESULT_OK) {
            // ??????????????????
            List<String> removeMemberList = data.getStringArrayListExtra(IntentExtra.LIST_STR_ID_LIST);
            SLog.i(TAG, "removeMemberList.size(): " + removeMemberList.size());
            groupDetailViewModel.removeGroupMember(removeMemberList);
        }
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_PERMISSION && !CheckPermissionUtils.allPermissionGranted(grantResults)) {
            List<String> permissionsNotGranted = new ArrayList<>();
            for (String permission : permissions) {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                    permissionsNotGranted.add(permission);
                }
            }
            if (permissionsNotGranted.size() > 0) {
                DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case DialogInterface.BUTTON_POSITIVE:
                                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                Uri uri = Uri.fromParts("package", getPackageName(), null);
                                intent.setData(uri);
                                startActivityForResult(intent, requestCode);
                                break;
                            case DialogInterface.BUTTON_NEGATIVE:
                                break;
                            default:
                                break;
                        }
                    }
                };
                CheckPermissionUtils.showPermissionAlert(this, getResources().getString(R.string.seal_grant_permissions) + CheckPermissionUtils.getNotGrantedPermissionMsg(this, permissionsNotGranted), listener);
            } else {
                ToastUtils.showToast(getString(R.string.seal_set_clean_time_fail));
            }
        } else {
            //?????????????????????????????????????????????
            groupDetailViewModel.setScreenCaptureStatus(1);
        }
    }
}
