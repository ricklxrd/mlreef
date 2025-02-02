import initialState from './initialState';
import * as types from '../actionTypes';

export default (state = initialState.actionModal, action) => {
  switch (action.type) {
    case types.ACTION_MODAL_RESET_STATE:
      return {
        ...initialState.actionModal,
      };

    case types.ACTION_MODAL_SET_VALUES:
      return {
        ...state,
        isShown: true,
        ...action.values,
      };

    default:
      return state;
  }
};
