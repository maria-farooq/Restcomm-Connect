'use strict';

var rcMod = angular.module('rcApp');

rcMod.controller('UserMenuCtrl', function($scope, $http, $resource, $rootScope, $location, $modal, AuthService, Notifications, RCommAccounts, $state) {

  /* watch location change and update root scope variable for rc-*-pills */
  $rootScope.$on('$locationChangeStart', function(/*event, next, current*/) {
    $rootScope.location = $location.path();
  });

  //$scope.auth = AuthService;
  //$scope.sid = SessionService.get('sid');
  $scope.friendlyName = AuthService.getFrientlyName();

  $scope.testNotifications = function() {
    Notifications.info('This is an info message');
    Notifications.warn('This is an warning message');
    Notifications.error('This is an error message');
    Notifications.success('This is an success message');
  };

  $scope.logout = function() {
    AuthService.logout();
    $state.go('public.login');
  };

  //if(AuthService.isLoggedIn()) {
    var accountsList = RCommAccounts.query(function() {
      $scope.accountsList = accountsList;
      for (var x in accountsList){
        if(accountsList[x].sid == $scope.sid) {
          $scope.currentAccount = accountsList[x];
        }
      }
    });
  //}

  // add account -------------------------------------------------------------

  $scope.showRegisterAccountModal = function () {
    var registerAccountModal = $modal.open({
      controller: RegisterAccountModalCtrl,
      scope: $scope,
      templateUrl: 'modules/modals/modal-register-account.html'
    });

    registerAccountModal.result.then(
      function () {
        // what to do on modal completion...
      },
      function () {
        // what to do on modal dismiss...
      }
    );
  };

  $scope.showAboutModal = function () {
    $modal.open({
      controller: AboutModalCtrl,
      scope: $scope,
      windowClass: 'temp-modal-lg',
      templateUrl: 'modules/modals/modal-about.html'
    });
  };

});

rcMod.controller('ProfileCtrl', function($scope, $resource, $stateParams, SessionService, RCommAccounts, md5) {
  //$scope.sid = SessionService.get('sid');

  var accountBackup;

  $scope.$watch('account', function() {
    if (!angular.equals($scope.account, accountBackup)) {
      $scope.accountChanged = true;
      // console.log('CHANGED: ' + $scope.accountChanged + ' => VALID:' + $scope.profileForm.$valid);
    }
  }, true);

  $scope.newPassword = $scope.newPassword2 = '';

  $scope.$watchCollection('[newPassword, newPassword2]', function() {
    if($scope.newPassword == '' && $scope.newPassword2 == '') {
      $scope.profileForm.newPassword.$valid = $scope.profileForm.newPassword2.$valid = true;
      $scope.accountValid = $scope.profileForm.$valid;
      if($scope.account) {
        $scope.account.auth_token = accountBackup.auth_token;
      }
      return;
    }
    var valid = angular.equals($scope.newPassword, $scope.newPassword2);
    $scope.profileForm.newPassword.$valid = $scope.profileForm.newPassword2.$valid = valid;
    $scope.accountValid = $scope.profileForm.$valid && valid;
    $scope.account.auth_token = '<modified></modified>';
    // console.log('NP [' + $scope.profileForm.newPassword.$valid + '] NP2 [' + $scope.profileForm.newPassword2.$valid + '] FORM [' + $scope.profileForm.$valid + ']');
  }, true);

  $scope.resetChanges = function() {
    $scope.newPassword = $scope.newPassword2 = '';
    $scope.account = angular.copy(accountBackup);
    $scope.accountChanged = false;
  };

  $scope.updateProfile = function() {
    var params = {FriendlyName: $scope.account.friendly_name, Type: $scope.account.type, Status: $scope.account.status};

    if($scope.newPassword != '' && $scope.profileForm.newPassword.$valid) {
      params['Auth_Token'] = md5.createHash($scope.newPassword);
    }

    RCommAccounts.update({accountSid:$scope.account.sid}, $.param(params), function() { // success
      if($scope.account.sid === SessionService.get('sid')) {
        SessionService.set('logged_user', $scope.account.friendly_name);
      }
      $scope.showAlert('success', 'Profile Updated Successfully.');
      $scope.getAccounts();
    }, function() { // error
      // TODO: Show alert
      $scope.showAlert('error', 'Failure Updating Profile. Please check data and try again.');
    });
  };

  $scope.alert = {};

  $scope.showAlert = function(type, msg) {
    $scope.alert.type = type;
    $scope.alert.msg = msg;
    $scope.alert.show = true;
  };

  $scope.closeAlert = function() {
    $scope.alert.type = '';
    $scope.alert.msg = '';
    $scope.alert.show = false;
  };

  // Start with querying for accounts...
  $scope.getAccounts = function() {
    $scope.accounts = RCommAccounts.query(function(data){
      angular.forEach(data, function(value){
        if(value.sid == $stateParams.accountSid) {
          $scope.account = angular.copy(value);
          accountBackup = angular.copy(value);
        }
      });
      $scope.resetChanges();
    });
  };

  $scope.getAccounts();

});

// Register Account Modal

var RegisterAccountModalCtrl = function ($scope, $modalInstance, RCommAccounts, Notifications) {

  $scope.statuses = ['ACTIVE','UNINITIALIZED','SUSPENDED','INACTIVE','CLOSED'];
  $scope.newAccount = {role: 'Administrator'};
  $scope.createAccount = function(account) {
    if(account.email && account.password) {
      // Numbers.register({PhoneNumber:number.number});
      account.friendlyName = account.friendlyName || account.email;
      RCommAccounts.register($.param(
        {
          EmailAddress : account.email,
          Password: account.password,
          Role: account.role,
          Status: account.status,
          FriendlyName: account.friendlyName ? account.friendlyName : account.email
        }),
        function() { // success
          Notifications.success('Account "' + account.friendlyName + '" created successfully!');
          $modalInstance.close();
        },
        function(response) { // error
        	if (response.status == 409)
        		Notifications.error("User already exists.");
        	else
        		Notifications.error('Required fields are missing.');
        }
      );
    }
    else {
      Notifications.error('Required fields are missing.');
    }
  };

  $scope.cancel = function () {
    $modalInstance.dismiss('cancel');
  };
};

var AboutModalCtrl = function($scope, $modalInstance, RCommJMX, RCVersion) {

	$scope.Math = window.Math;

	$scope.getData = function() {
		$scope.version = RCVersion.get({
			accountSid : $scope.sid
		}, function(data) {
			if (data) {
				var version = $scope.version;
				var pattern = /(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})/;
				$scope.releaseDate = new Date(data.Date.replace(pattern,
						'$1-$2-$3 $4:$5'));
			}

		}, function() {
		});
		$scope.info = RCommJMX.get({
			path : 'java.lang:type=*'
		}, function(data) {
			$scope.OS = data.value['java.lang:type=OperatingSystem'];
			$scope.JVM = data.value['java.lang:type=Runtime'];
			$scope.Memory = data.value['java.lang:type=Memory'];
			$scope.Threads = data.value['java.lang:type=Threading'];
		}, function() {
		});
	};

	$scope.cancel = function() {
		$modalInstance.dismiss('cancel');
	};

	$scope.getData();
};
