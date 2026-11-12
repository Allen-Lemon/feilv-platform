<template>
  <view>
    <view class="card" v-for="a in addresses" :key="a.id">
      <view class="top"><text class="name">{{ a.contact }}</text><text>{{ a.phone }}</text><text v-if="a.isDefault" class="tag">默认</text></view>
      <view class="addr">{{ a.poiName }} {{ a.address }}</view>
      <view class="actions"><button size="mini" @click="edit(a)">编辑</button><button size="mini" type="warn" @click="remove(a.id)">删除</button></view>
    </view>
    <view class="card"><view class="title">{{ editing ? '编辑地址' : '新增地址' }}</view><input v-model="form.contact" placeholder="联系人" class="input" /><input v-model="form.phone" placeholder="联系电话" class="input" /><input v-model="form.poiName" placeholder="地点名称（选填）" class="input" /><input v-model="form.address" placeholder="详细地址" class="input" /><switch :checked="form.isDefault === 1" @change="form.isDefault = $event.detail.value ? 1 : 0" /> <text>设为默认</text><button class="btn-primary" @click="save">保存地址</button></view>
  </view>
</template>
<script setup>
import { onShow } from '@dcloudio/uni-app'
import { reactive, ref } from 'vue'
import api from '../../api/index'
const addresses=ref([]); const editing=ref(false); const editingId=ref(null); const form=reactive({ contact:'', phone:'', poiName:'', address:'', isDefault:0 })
const load=async()=>{ addresses.value=await api.addressList() }
const reset=()=>{ Object.assign(form,{contact:'',phone:'',poiName:'',address:'',isDefault:0}); editing.value=false; editingId.value=null }
const edit=a=>{ Object.assign(form,a); editing.value=true; editingId.value=a.id }
const save=async()=>{ if(!form.contact||!/^1\d{10}$/.test(form.phone)||!form.address) return uni.showToast({title:'请完整填写联系人、手机号和地址',icon:'none'}); editingId.value?await api.updateAddress(editingId.value,form):await api.addAddress(form); reset(); load() }
const remove=async id=>{ await api.deleteAddress(id); load() }
onShow(load)
</script>
<style>.title{font-weight:600;margin-bottom:18rpx}.top{display:flex;gap:20rpx;align-items:center}.name{font-weight:600}.tag{color:#ff7d00;background:#fff2e0;padding:4rpx 12rpx;font-size:22rpx}.addr{color:#606266;margin-top:12rpx}.actions{text-align:right;margin-top:12rpx}.input{background:#f5f6f8;padding:18rpx;margin-bottom:14rpx;border-radius:10rpx}.btn-primary{margin-top:18rpx}</style>
